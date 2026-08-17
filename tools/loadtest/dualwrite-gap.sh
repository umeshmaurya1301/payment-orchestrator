#!/usr/bin/env bash
#
# Phase 6a: measure the dual-write gap, before building the outbox that closes it.
#
#   tools/loadtest/dualwrite-gap.sh
#
# THE CLAIM UNDER TEST
#
# "You cannot write to the database and publish to Kafka atomically." Everyone
# repeats it; this measures it. Three windows:
#
#   1. Kafka healthy      payments == events
#   2. Kafka DOWN         payments succeed, events are LOST
#   3. Kafka healthy      payments == events again
#
# The number that matters is the gap after window 3. If the deficit from window 2
# is still there once the brokers are back, the loss was PERMANENT - nothing in
# the system remembered the events it owed, because the only record of the debt
# was an in-memory call stack that has long since unwound.
#
# WHY THE PAYMENTS STILL SUCCEED
#
# That is the whole reason this failure mode survives in real systems. The
# merchant gets a 201, the card is authorized, the database is correct, and
# nothing looks like an outage. Only the downstream ledger is wrong, and it has
# no way to know it.
#
# WHY THE BROKERS ARE STOPPED RATHER THAN PROXIED
#
# Toxiproxy fronts MySQL and Redis in this project, not Kafka. Stopping the
# containers is a blunter fault and a more honest one for this test: the point is
# not a slow broker, it is no broker at all.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EDGE="${EDGE:-http://localhost:8080}"
API_KEY="${API_KEY:-pk_test_dev_merchant_key}"
N="${N:-20}"
TOPIC="${TOPIC:-payment.events}"

mysql_q() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

# Sum of the end offsets across every partition = messages ever written.
#
# kafka-get-offsets.sh, NOT `kafka-run-class.sh kafka.tools.GetOffsetShell`. The
# latter is the old entry point; on Kafka 4 it prints nothing and exits 0, so the
# first version of this script confidently reported zero events while ten were
# sitting in the topic. A counter that returns 0 on error rather than failing is
# the worst possible instrument for an experiment whose finding IS a zero.
count_events() {
    local out
    out="$(MSYS_NO_PATHCONV=1 docker exec payorch-kafka-1 \
        /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka-1:9092 \
        --topic "${TOPIC}" 2>/dev/null | tr -d '\r')"
    if [[ -z "$out" ]]; then
        echo "ERROR: could not read offsets for ${TOPIC}" >&2
        return 1
    fi
    echo "$out" | awk -F: '{s+=$3} END {print s+0}'
}

count_terminal_payments() {
    mysql_q "SELECT COUNT(*) FROM payment WHERE state IN ('AUTHORIZED','FAILED','UNKNOWN');"
}

send() {
    local n=$1 tag=$2
    for i in $(seq 1 "$n"); do
        curl -s --max-time 30 -o /dev/null -X POST "${EDGE}/v1/payments" \
            -H "Content-Type: application/json" \
            -H "X-Api-Key: ${API_KEY}" \
            -H "Idempotency-Key: dw-${tag}-$(date +%s%N)-$RANDOM" \
            -d '{"amountMinor":4200,"currency":"INR","card":{"number":"4242424242424242","expiryMonth":12,"expiryYear":2030,"cvv":"123"},"merchantReference":"dualwrite"}'
    done
}

brokers_up() {
    docker start payorch-kafka-1 payorch-kafka-2 payorch-kafka-3 >/dev/null 2>&1
    # Wait for the topic to be readable again rather than sleeping a guess.
    for _ in $(seq 1 60); do
        count_events >/dev/null 2>&1 && return 0
        sleep 2
    done
    echo "brokers did not come back" >&2
    return 1
}

trap 'brokers_up >/dev/null 2>&1' EXIT

publisher="$(docker inspect payorch-payment-orchestrator \
    --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep '^EVENTS_PUBLISHER=' | cut -d= -f2)"
echo "publisher under test: ${publisher:-unset}"
if [[ "${publisher}" != "direct" && "${publisher}" != "outbox" ]]; then
    echo "  refusing to run: set EVENTS_PUBLISHER=direct (or outbox) and redeploy" >&2
    exit 2
fi

echo
echo "=== 1. Kafka healthy: ${N} payments ==="
brokers_up
E0="$(count_events)"; P0="$(count_terminal_payments)"
send "$N" healthy
sleep 3
E1="$(count_events)"; P1="$(count_terminal_payments)"
echo "   payments +$((P1-P0))   events +$((E1-E0))"

echo
echo "=== 2. Kafka DOWN: ${N} payments ==="
docker stop payorch-kafka-1 payorch-kafka-2 payorch-kafka-3 >/dev/null
echo "   all three brokers stopped"
send "$N" broken
P2="$(count_terminal_payments)"
echo "   payments +$((P2-P1))   events: cannot be counted, the cluster is down"

echo
echo "=== 3. Kafka back: ${N} payments ==="
brokers_up
sleep 5
E3_before="$(count_events)"
send "$N" recovered
sleep 3
E3="$(count_events)"; P3="$(count_terminal_payments)"
echo "   payments +$((P3-P2))   events +$((E3-E3_before))"

echo
echo "=============================================================="
echo " RESULT"
echo "=============================================================="
printf "   terminal payments   %6d\n" "$((P3-P0))"
printf "   events published    %6d\n" "$((E3-E0))"
GAP=$(( (P3-P0) - (E3-E0) ))
printf "   permanent gap       %6d\n" "$GAP"
echo
if [[ "$GAP" -gt 0 ]]; then
    echo "   ${GAP} payments are AUTHORIZED in MySQL and were never announced."
    echo "   The brokers are healthy again and the gap did not close, because"
    echo "   nothing recorded that the events were owed. A downstream ledger is"
    echo "   now permanently short, and no error was returned to anybody."
else
    echo "   No gap. Every terminal payment produced an event, including those"
    echo "   accepted while the cluster was unreachable - the events waited in"
    echo "   the outbox and were relayed when the brokers returned."
fi
echo
