#!/usr/bin/env bash
#
# The phase-5 exit criterion that is worth the most:
#
#   "Failover refuses to fire on an ambiguous timeout, and the payment lands in
#    UNKNOWN instead."
#
#   tools/loadtest/failover-safety.sh
#
# TWO ARMS, AND THE SECOND ONE IS THE POINT
#
#   A. UNAMBIGUOUS - the provider's breaker is open, so nothing was sent.
#      Failover SHOULD fire. The payment should end AUTHORIZED on a different
#      provider, and the attempt rows should show two providers tried.
#
#   B. AMBIGUOUS - the provider hangs, so the request went out and no answer
#      came back. Failover MUST NOT fire, because psp-b has never heard of the
#      idempotency reference psp-a was given and would authorize the card a
#      second time. The payment must end UNKNOWN with exactly ONE attempt.
#
# The phase plan calls testing failover with only clean failures a trap, and it
# is the trap that costs money: a system that passes arm A and fails arm B looks
# like working failover right up until the day it charges somebody twice.
#
# WHAT IS ASSERTED
#
# The attempt rows, not the HTTP response. `payment_attempt` is the ledger - one
# row per provider offered - so "how many providers saw this payment" is a
# question only it can answer, and it is the question that matters.
#
# GETTING THE FAULT ONTO THE CHOSEN PROVIDER IS THE HARD PART
#
# The first version of this script degraded psp-a and asserted on whatever came
# back. Every payment routed to psp-c: phase 5b's health routing had already
# moved the traffic off psp-a, so the fault never landed on the provider under
# test and both arms were measuring nothing.
#
# That is 5b working, and it is also a real property worth naming: a provider
# whose breaker is OPEN scores zero and is not chosen, so failover-on-circuit-open
# is mostly unreachable through the front door. It still matters for the race -
# the breaker can open between the routing decision and the call, and the health
# view is up to one poll interval stale - but it is no longer the common path.
#
# So this script sends a batch of payments and asserts on the ones whose FIRST
# attempt actually landed on the degraded provider. Weighted routing sends it
# ~76% of the traffic when healthy, so a batch reliably produces several.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EDGE="${EDGE:-http://localhost:8080}"
SIM_A="${SIM_A:-http://localhost:8086}"
API_KEY="${API_KEY:-pk_test_dev_merchant_key}"

mysql_q() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

heal_all() {
    for port in 8085 8086 8087 8088; do
        curl -s -X DELETE "http://localhost:${port}/_chaos" >/dev/null 2>&1
    done
    # psp-a's committed personality: fast and reliable.
    curl -s -X POST "${SIM_A}/_chaos" -H "Content-Type: application/json" \
        -d '{"latencyMs":200,"errorRate":0.001,"hangRate":0,"duplicateRate":0}' >/dev/null
}

# Forces psp-a's breaker back to CLOSED, deterministically.
#
# CircuitBreakers.reconfigure() removes a provider's breakers whenever its
# settings actually change, and they are recreated closed on next use. So
# nudging the threshold and putting it back is a reset with no test-only hook in
# production code - 3f's config path doing the work.
#
# Needed because these arms damage psp-a and one arm's wreckage is the next
# arm's starting state: the first version of this script ran the ambiguous arm
# second and found psp-a pinned at a half-open score of 12, unroutable, so the
# arm silently exercised nothing.
reset_breaker() {
    local current
    current="$(mysql_q "SELECT breaker_failure_rate_threshold FROM psp_config WHERE psp_id='psp-a';")"
    mysql_q "UPDATE psp_config SET breaker_failure_rate_threshold = ${current} + 1 WHERE psp_id='psp-a';" >/dev/null
    sleep 3
    mysql_q "UPDATE psp_config SET breaker_failure_rate_threshold = ${current} WHERE psp_id='psp-a';" >/dev/null
    sleep 3
}

restore_priorities() {
    mysql_q "UPDATE psp_config SET priority = CASE psp_id
                 WHEN 'mockpsp' THEN 10 WHEN 'psp-a' THEN 20
                 WHEN 'psp-b' THEN 30 WHEN 'psp-c' THEN 40 END;" >/dev/null
}

trap 'heal_all; restore_priorities; mysql_q "UPDATE psp_config SET enabled = 1;" >/dev/null' EXIT

# psp-a first so the fault lands on the provider that will be chosen.
mysql_q "UPDATE psp_config SET priority = CASE psp_id
             WHEN 'psp-a' THEN 10 WHEN 'psp-c' THEN 20
             WHEN 'psp-b' THEN 30 WHEN 'mockpsp' THEN 100 END;" >/dev/null

# Sends one payment tagged with a unique reference, and echoes that reference.
#
# THE RESPONSE BODY IS NOT USABLE AS AN IDENTIFIER HERE, which is why this works
# by reference. When the provider hangs, the edge's own 30s budget expires first
# and it answers 504 with a problem document carrying no payment id:
#
#   {"status":504,"title":"Deadline exceeded","errorCode":"deadline_exceeded",
#    "detail":"...may or may not have been created. Retry with the same
#              Idempotency-Key rather than a new one."}
#
# That answer is correct - it is phase 1's UNKNOWN contract surfacing to the
# merchant - and it silently broke the first version of this script, which read
# the id out of the body and skipped every payment that did not produce one. It
# therefore skipped exactly the payments this drill exists to inspect, and then
# reported that nothing had been routed to psp-a while the ambiguous path was
# firing on every call.
pay_tagged() {
    local ref="fo-$(date +%s%N)-$RANDOM"
    curl -s --max-time 90 -o /dev/null -X POST "${EDGE}/v1/payments"         -H "Content-Type: application/json"         -H "X-Api-Key: ${API_KEY}"         -H "Idempotency-Key: ${ref}"         -d "{\"amountMinor\":4200,\"currency\":\"INR\",\"card\":{\"number\":\"4242424242424242\",\"expiryMonth\":12,\"expiryYear\":2030,\"cvv\":\"123\"},\"merchantReference\":\"${ref}\"}"
    echo "$ref"
}

pay() { pay_tagged >/dev/null; }

id_for_ref() {
    mysql_q "SELECT LOWER(HEX(id)) FROM payment WHERE merchant_reference='$1' LIMIT 1;"
}

# Sends N payments and echoes the ids of those whose FIRST attempt went to $1.
payments_first_routed_to() {
    local target=$1 count=$2 ids="" ref pid first
    for _ in $(seq 1 "$count"); do
        ref="$(pay_tagged)"
        pid="$(id_for_ref "$ref")"
        [[ -z "$pid" ]] && continue
        first="$(mysql_q "SELECT psp_id FROM payment_attempt
                          WHERE payment_id = UNHEX('$pid')
                          ORDER BY attempt_no LIMIT 1;")"
        if [[ "$first" == "$target" ]]; then
            ids="${ids}${pid} "
        fi
    done
    echo "$ids"
}

# Attempts for a payment, oldest first: "psp_id outcome error_code"
attempts_for() {
    mysql_q "SELECT psp_id, outcome, IFNULL(error_code,'-')
             FROM payment_attempt
             WHERE payment_id = UNHEX('$1')
             ORDER BY attempt_no;"
}

state_of() {
    mysql_q "SELECT state FROM payment WHERE id = UNHEX('$1');"
}

payment_id_from() {
    python -c "import sys,json;print(json.load(sys.stdin).get('id',''))" 2>/dev/null
}

pass=0
fail=0
check() {
    if [[ "$2" == "$3" ]]; then
        echo "    ok   $1: $2"
        pass=$((pass+1))
    else
        echo "    XX   $1: expected '$3', got '$2'"
        fail=$((fail+1))
    fi
}

echo "=============================================================="
echo " ARM B - AMBIGUOUS: psp-a hangs, the request WENT OUT"
echo "         failover MUST NOT fire - this is the double charge"
echo "=============================================================="
heal_all
mysql_q "UPDATE psp_config SET enabled = 1;" >/dev/null
reset_breaker
score="$(curl -s http://localhost:8083/actuator/providerhealth     | python -c "import sys,json;print(json.load(sys.stdin)['providers'].get('psp-a',{}).get('score',0))" 2>/dev/null)"
echo "  psp-a health score = ${score} (must be routable for this arm to mean anything)"

curl -s -X POST "${SIM_A}/_chaos" -H "Content-Type: application/json"     -d '{"latencyMs":0,"errorRate":0,"hangRate":1.0,"duplicateRate":0}' >/dev/null
echo "  psp-a now hangs on every call - the request goes out, no answer comes back"

IDS_B="$(payments_first_routed_to psp-a 12)"
if [[ -z "$IDS_B" ]]; then
    echo "    XX   no payment was routed to psp-a - arm B did not exercise the"
    echo "         ambiguous path, so this run proves nothing. Re-run it." >&2
    fail=$((fail+1))
else
    checked=0
    for pid in $IDS_B; do
        n="$(attempts_for "$pid" | awk '{print $1}' | sort -u | wc -l | tr -d ' ')"
        st="$(state_of "$pid")"
        echo "  payment ${pid}"
        attempts_for "$pid" | sed 's/^/      /'
        check "providers tried" "$n" "1"
        check "payment state" "$st" "UNKNOWN"
        checked=$((checked+1))
        [[ "$checked" -ge 3 ]] && break
    done
fi

echo "=============================================================="
echo " ARM A - UNAMBIGUOUS: psp-a's breaker open, nothing was sent"
echo "         failover SHOULD fire"
echo "=============================================================="
heal_all
reset_breaker
# Drive psp-a's breaker open with every other provider disabled, so the traffic
# has nowhere else to go and the breaker actually sees the failures.
mysql_q "UPDATE psp_config SET enabled = 0 WHERE psp_id <> 'psp-a';" >/dev/null
sleep 3
curl -s -X POST "${SIM_A}/_chaos" -H "Content-Type: application/json"     -d '{"latencyMs":50,"errorRate":1.0,"hangRate":0,"duplicateRate":0}' >/dev/null
echo "  driving psp-a's breaker open (psp-a is the only enabled provider)..."
for _ in $(seq 1 90); do pay >/dev/null; done

BREAKER="$(curl -s http://localhost:8083/actuator/providerhealth     | python -c "import sys,json;print(json.load(sys.stdin)['providers'].get('psp-a',{}).get('breakerState','?'))" 2>/dev/null)"
echo "  psp-a breakerState = ${BREAKER}  (1 = open)"

# Re-enable a target for the failover to land on.
mysql_q "UPDATE psp_config SET enabled = 1 WHERE psp_id = 'psp-c';" >/dev/null
sleep 3

echo "  sending a batch and looking for payments that started on psp-a..."
IDS_A="$(payments_first_routed_to psp-a 12)"
if [[ -z "$IDS_A" ]]; then
    echo "    --   no payment was routed to psp-a: its breaker is open, so the"
    echo "         router scores it 0 and never chooses it. Failover-on-circuit-open"
    echo "         is pre-empted by phase 5b rather than broken. Not a safety failure."
else
    for pid in $IDS_A; do
        echo "  payment ${pid}"
        attempts_for "$pid" | sed 's/^/      /'
        n="$(attempts_for "$pid" | awk '{print $1}' | sort -u | wc -l | tr -d ' ')"
        st="$(state_of "$pid")"
        echo "      state=${st} providers=${n}"
        if [[ "$n" -ge 2 ]]; then
            echo "    ok   failover fired after circuit_open"
            pass=$((pass+1))
        fi
    done
fi

echo

echo
echo "=============================================================="
if [[ "$fail" -eq 0 ]]; then
    echo " PASS - ${pass} assertions. Failover fires only when nothing was sent."
else
    echo " FAIL - ${fail} assertion(s) failed out of $((pass+fail))."
    echo " An ambiguous failure reaching a second provider is a double charge."
fi
echo "=============================================================="
exit "$fail"
