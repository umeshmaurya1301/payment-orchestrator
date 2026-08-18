#!/usr/bin/env bash
#
# Phase 6j: does the ledger tell an authorization from a capture, and what does
# it cost when the capture succeeds and the ledger does not hear about it?
#
#   tools/loadtest/capture-ledger.sh
#
# WHY A SECOND EVENT PER PAYMENT IS NOT BOOKKEEPING PEDANTRY
#
# Until this phase a payment produced one event and the ledger posted one pair of
# legs on AUTHORIZED. That is a ledger that cannot tell a hold from a debit -
# money the merchant has been PROMISED from money that has actually been
# COLLECTED - and the two differ by however many authorizations are outstanding
# at any moment, which for a real acquirer is a large number that moves all day.
#
#   AUTHORIZED   merchant     +amount      the merchant is owed this
#                clearing     -amount      and we carry the liability
#
#   CAPTURED     clearing     +amount      the funds arrive
#                network      -amount      from the card network
#
# Both pairs sum to zero, so the table-wide invariant from phase 6e is untouched.
# What the second pair buys is a number that did not exist before: the clearing
# balance is EXACTLY the outstanding authorized-but-uncaptured exposure, and it
# returns to zero for a payment once that payment is captured.
#
# THREE ARMS
#
#   A  authorize only     clearing goes negative by exactly the authorized total
#   B  then capture       clearing returns, per payment, to zero
#   C  capture with the   the provider takes the money and the ledger does not
#      ledger failing     hear. This is the gap the saga in 6k has to close, and
#                         it is measured here BEFORE anything is built to fix it

set -uo pipefail

EDGE="${EDGE:-http://localhost:8080}"
LEDGER="${LEDGER:-http://localhost:8084}"
API_KEY="${API_KEY:-pk_test_dev_merchant_key}"
N="${N:-12}"

FAIL=0

# --------------------------------------------------------------- plumbing ---

lq() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch_ledger -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

pq() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

hexof() {
    echo "$1" | tr -d '-'
}

balance() {
    lq "SELECT COALESCE(balance_minor, 0) FROM ledger_account WHERE account_ref = '$1';"
}

# Legs for one payment, by entry type. The question this answers is not "did the
# ledger post" but "did it post the RIGHT pair" - a capture recorded as a second
# authorization would balance perfectly and be wrong.
legs_for() {
    lq "SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entry
        WHERE payment_id = UNHEX('$(hexof "$1")') AND entry_type = '$2';"
}

entries_for() {
    lq "SELECT COUNT(*) FROM ledger_entry WHERE payment_id = UNHEX('$(hexof "$1")');"
}

clearing_for() {
    lq "SELECT COALESCE(SUM(e.amount_minor), 0)
        FROM ledger_entry e JOIN ledger_account a ON a.id = e.account_id
        WHERE e.payment_id = UNHEX('$(hexof "$1")') AND a.account_ref = 'settlement:clearing';"
}

imbalance() {
    lq "SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entry;"
}

outbox_pending() {
    pq "SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL;"
}

arm_seam() {
    curl -s -o /dev/null -X POST "${LEDGER}/actuator/chaosseams/ledger-consumer" \
        -H 'content-type: application/json' \
        -d "{\"action\":\"FAIL\",\"probability\":$1}"
}

disarm_seam() {
    curl -s -o /dev/null -X DELETE "${LEDGER}/actuator/chaosseams"
}

create_payment() {
    curl -s --max-time 30 -X POST "${EDGE}/v1/payments" \
        -H "Content-Type: application/json" \
        -H "X-Api-Key: ${API_KEY}" \
        -H "Idempotency-Key: cap-$(date +%s%N)-$RANDOM" \
        -d '{"amountMinor":4200,"currency":"INR","card":{"number":"4242424242424242","expiryMonth":12,"expiryYear":2030,"cvv":"123"},"merchantReference":"capture-ledger"}' \
        | python -c "
import json,sys
try: print(json.load(sys.stdin).get('id',''))
except Exception: print('')
" 2>/dev/null
}

capture_payment() {
    curl -s -o /dev/null -w '%{http_code}' --max-time 30 \
        -X POST "${EDGE}/v1/payments/$1/capture" -H "X-Api-Key: ${API_KEY}"
}

payment_state() {
    pq "SELECT state FROM payment WHERE id = UNHEX('$(hexof "$1")');"
}

wait_for_entries() {
    local id="$1" want="$2" budget="${3:-40}"
    for _ in $(seq 1 "${budget}"); do
        [[ "$(entries_for "${id}")" -ge "${want}" ]] && return 0
        sleep 1
    done
    return 1
}

chk() {
    if [[ "$2" == "$3" ]]; then
        printf "   ok   %-46s %s\n" "$1" "$2"
    else
        printf "   XX   %-46s %s (expected %s)\n" "$1" "$2" "$3"
        FAIL=$((FAIL+1))
    fi
}

# ------------------------------------------------------------- preflight ----

echo "=============================================================="
echo " PREFLIGHT"
echo "=============================================================="

publisher="$(docker inspect payorch-payment-orchestrator \
    --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
    | grep '^EVENTS_PUBLISHER=' | cut -d= -f2)"
if [[ "${publisher}" != "outbox" ]]; then
    echo "   EVENTS_PUBLISHER is '${publisher:-unset}', not 'outbox'." >&2
    exit 2
fi
echo "   publisher                      outbox"
disarm_seam
echo "   seams                          disarmed"

start_clearing="$(balance settlement:clearing)"
start_network="$(balance settlement:card-network)"
echo "   clearing balance               ${start_clearing:-0}"
echo "   card-network balance           ${start_network:-0}"
chk "the books start balanced" "$(imbalance)" "0"
echo

# ===================================================================== A =====

echo "=============================================================="
echo " ARM A - AUTHORIZE ONLY"
echo "=============================================================="
echo "   ${N} payments authorized and NOT captured. Every one of them is money"
echo "   the merchant has been promised and nobody has collected, so clearing"
echo "   should move by exactly minus the authorized total."
echo

ids=()
echo -n "   authorizing"
for _ in $(seq 1 "${N}"); do
    id="$(create_payment)"
    [[ -n "${id}" ]] && ids+=("${id}")
    echo -n "."
done
echo " ${#ids[@]} payments"

echo -n "   waiting for the ledger"
for id in "${ids[@]}"; do wait_for_entries "${id}" 2 || echo -n "!"; done
echo " done"

authorized_total=$(( ${#ids[@]} * 4200 ))
after_a_clearing="$(balance settlement:clearing)"
moved=$(( start_clearing - after_a_clearing ))

echo "   authorized total               ${authorized_total}"
echo "   clearing moved by              -${moved}"
chk "clearing carries the whole exposure" "${moved}" "${authorized_total}"
chk "no capture legs exist yet" "$(legs_for "${ids[0]}" CLEARING_CREDIT)" "0"
chk "two legs per authorized payment" "$(entries_for "${ids[0]}")" "2"
chk "the books still balance" "$(imbalance)" "0"
echo

# ===================================================================== B =====

echo "=============================================================="
echo " ARM B - THEN CAPTURE"
echo "=============================================================="
echo "   The same payments, captured. Clearing should come back to exactly"
echo "   where it started: the liability is discharged as the funds arrive."
echo

captured=0
echo -n "   capturing"
for id in "${ids[@]}"; do
    [[ "$(capture_payment "${id}")" == "200" ]] && captured=$((captured+1))
    echo -n "."
done
echo " ${captured} captured"

echo -n "   waiting for the ledger"
for id in "${ids[@]}"; do wait_for_entries "${id}" 4 || echo -n "!"; done
echo " done"

after_b_clearing="$(balance settlement:clearing)"
after_b_network="$(balance settlement:card-network)"

echo "   clearing balance               ${after_b_clearing}  (started ${start_clearing})"
echo "   card-network balance           ${after_b_network}  (started ${start_network})"

chk "every capture succeeded" "${captured}" "${#ids[@]}"
chk "clearing returns to where it began" "${after_b_clearing}" "${start_clearing}"
chk "the network funded the whole batch" \
    "$(( start_network - after_b_network ))" "${authorized_total}"
chk "four legs per captured payment" "$(entries_for "${ids[0]}")" "4"
chk "and they net to zero on clearing" "$(clearing_for "${ids[0]}")" "0"
chk "the payment is CAPTURED" "$(payment_state "${ids[0]}")" "CAPTURED"
chk "the books still balance" "$(imbalance)" "0"

echo
echo "   A SECOND CAPTURE IS REFUSED BY THE STATE MACHINE, NOT BY A KEY"
chk "capturing an already-captured payment" "$(capture_payment "${ids[0]}")" "409"
chk "and posts nothing new" "$(entries_for "${ids[0]}")" "4"
echo

# ===================================================================== C =====

echo "=============================================================="
echo " ARM C - THE GAP THE SAGA HAS TO CLOSE"
echo "=============================================================="
echo "   One payment captured while the ledger consumer is failing every"
echo "   record. The provider takes the money either way - it is a third"
echo "   party and no transaction of ours spans it - so for as long as the"
echo "   ledger cannot post, the customer has been charged and the books do"
echo "   not know."
echo
echo "   This is measured BEFORE anything is built to fix it, which is the"
echo "   rule this project runs on. Phase 6k is the compensating action."
echo

gap_id="$(create_payment)"
echo "   payment       ${gap_id}"
wait_for_entries "${gap_id}" 2 || echo "   (authorization did not post)"

arm_seam 1.0
echo "   seam 'ledger-consumer' armed to FAIL with probability 1.0"

status="$(capture_payment "${gap_id}")"
echo "   capture returned              ${status}"
echo -n "   draining the outbox"
for _ in $(seq 1 30); do
    [[ "$(outbox_pending)" == "0" ]] && break
    echo -n "."
    sleep 2
done
echo " done"
sleep 10

echo
echo "   WHAT EACH SYSTEM BELIEVES"
printf "   %-30s %s\n" "the provider" "captured (it answered 200)"
printf "   %-30s %s\n" "payment-orchestrator" "$(payment_state "${gap_id}")"
printf "   %-30s %s\n" "the ledger, capture legs" "$(legs_for "${gap_id}" CLEARING_CREDIT)"
printf "   %-30s %s\n" "the ledger, entries" "$(entries_for "${gap_id}")"
printf "   %-30s %s\n" "clearing for this payment" "$(clearing_for "${gap_id}")"

echo
chk "the orchestrator says the money moved" "$(payment_state "${gap_id}")" "CAPTURED"
chk "the ledger has no capture legs" "$(legs_for "${gap_id}" CLEARING_CREDIT)" "0"
chk "so clearing still carries the hold" "$(clearing_for "${gap_id}")" "-4200"
chk "and the books still balance - wrongly" "$(imbalance)" "0"

echo
echo "   Note the last one. Double entry does NOT catch this. The books balance"
echo "   perfectly while being wrong about the world, because the missing pair"
echo "   is missing on BOTH sides. An invariant over our own tables cannot see"
echo "   a disagreement with a third party, and that is the whole argument for"
echo "   reconciliation existing as well as double entry."
echo

echo "   RECOVERY: the ladder heals this when the cause is transient"
disarm_seam
# 120s, not 40, and the reason is the ladder itself. By the time the checks
# above have run, the record has already failed on the main topic AND on the
# 5-second tier, so it is sitting in the 1-minute one. Fixing the cause does not
# mean recovering at the speed you fixed it - the message is waiting out a timer
# that was set before you fixed anything. That is the cost of non-blocking
# retries and it is invisible until somebody times a recovery.
echo "   seam disarmed - the record is already on a tier, so this takes a minute"
if wait_for_entries "${gap_id}" 4 120; then
    printf "   ok   %-46s %s\n" "the retry ladder closed the gap" "$(entries_for "${gap_id}")"
else
    printf "   XX   %-46s %s\n" "the retry ladder closed the gap" "$(entries_for "${gap_id}")"
    FAIL=$((FAIL+1))
fi
chk "clearing nets to zero after recovery" "$(clearing_for "${gap_id}")" "0"
chk "the books balance" "$(imbalance)" "0"

echo
echo "   The ladder covers a transient failure. It does not cover a permanent"
echo "   one: at the end of 5s, 1m and 10m the record lands in the DLQ and"
echo "   nothing reverses the capture. That is what 6k is for."
echo

# ------------------------------------------------------------------ end -----

echo "=============================================================="
if [[ "${FAIL}" -eq 0 ]]; then
    echo " PASS - the ledger tells a hold from a debit, clearing measures the"
    echo "        uncaptured exposure, and the gap is real and measured."
else
    echo " FAIL - ${FAIL} check(s) failed."
fi
echo "=============================================================="
exit "${FAIL}"
