#!/usr/bin/env bash
#
# Phase 6e: is the ledger correct, and does it stay correct under replay?
#
#   tools/loadtest/ledger-convergence.sh          # check
#   tools/loadtest/ledger-convergence.sh replay   # replay the whole topic, then check
#
# THE FOUR ASSERTIONS
#
#   1. sum of all entries == 0          the double-entry invariant
#   2. no event has more than two legs  consumer-side idempotency
#   3. entries == 2 x events posted     every posting is balanced
#   4. merchant + clearing == 0         the balances agree with the entries
#
# WHY NOT "THE BALANCE DID NOT CHANGE"
#
# Because that test is wrong, and it produced a false failure the first time this
# was run by hand. Replaying a topic legitimately changes the balance whenever
# the consumer had previously SKIPPED records - which it had, 312 of them, after
# a deserialization fault exhausted their retries and the default handler moved
# on. The balance rose because the ledger was catching up, not double-counting.
#
# The distinction matters because it is the whole point of replay: a ledger that
# refuses to change on replay cannot recover from a bad deploy. What must not
# change is the INVARIANT - and "no event posted twice" is what actually
# expresses that.

set -uo pipefail

MODE="${1:-check}"

lq() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch_ledger -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

kafka_messages() {
    MSYS_NO_PATHCONV=1 docker exec payorch-kafka-1 \
        /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka-1:9092 \
        --topic payment.events 2>/dev/null | awk -F: '{s+=$3} END {print s+0}'
}

journal_docs() {
    docker exec payorch-mongo mongosh payorch_ledger --quiet \
        --eval 'db.journal.countDocuments({})' 2>/dev/null | tail -1 | tr -d '\r'
}

if [[ "$MODE" == "replay" ]]; then
    echo "=== replaying every message in payment.events ==="
    docker stop payorch-ledger-notifier >/dev/null
    MSYS_NO_PATHCONV=1 docker exec payorch-kafka-1 \
        /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka-1:9092 \
        --group ledger-notifier --reset-offsets --to-earliest \
        --topic payment.events --execute >/dev/null 2>&1
    docker start payorch-ledger-notifier >/dev/null
    echo -n "   waiting for the consumer"
    until curl -s http://localhost:8084/actuator/health 2>/dev/null | grep -q '"status":"UP"'; do
        echo -n "."; sleep 5
    done
    echo
    # Settle: the replay has to work through the whole topic.
    prev=-1
    for _ in $(seq 1 40); do
        now="$(lq 'SELECT COUNT(*) FROM ledger_entry;')"
        [[ "$now" == "$prev" ]] && break
        prev="$now"; sleep 3
    done
fi

MESSAGES="$(kafka_messages)"
EVENTS="$(lq 'SELECT COUNT(DISTINCT event_id) FROM ledger_entry;')"
ENTRIES="$(lq 'SELECT COUNT(*) FROM ledger_entry;')"
IMBALANCE="$(lq 'SELECT COALESCE(SUM(amount_minor),0) FROM ledger_entry;')"
OVERPOSTED="$(lq 'SELECT COUNT(*) FROM (SELECT event_id FROM ledger_entry GROUP BY event_id HAVING COUNT(*) > 2) x;')"
BALSUM="$(lq 'SELECT COALESCE(SUM(balance_minor),0) FROM ledger_account;')"
DOCS="$(journal_docs)"

echo
echo "=============================================================="
echo " LEDGER"
echo "=============================================================="
printf "   kafka messages          %8s\n" "$MESSAGES"
printf "   events posted           %8s\n" "$EVENTS"
printf "   journal documents       %8s\n" "$DOCS"
printf "   ledger entries          %8s\n" "$ENTRIES"
echo
fail=0
chk() {
    if [[ "$2" == "$3" ]]; then
        printf "   ok   %-34s %s\n" "$1" "$2"
    else
        printf "   XX   %-34s %s (expected %s)\n" "$1" "$2" "$3"
        fail=$((fail+1))
    fi
}
chk "double-entry invariant (sum=0)"   "$IMBALANCE"  "0"
chk "events posted more than once"     "$OVERPOSTED" "0"
chk "entries == 2 x events"            "$ENTRIES"    "$((EVENTS * 2))"
chk "balances sum to zero"             "$BALSUM"     "0"

echo
docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch_ledger \
    -e "SELECT account_ref, currency, balance_minor FROM ledger_account ORDER BY balance_minor DESC;" 2>/dev/null | grep -v Warning | sed 's/^/   /'

echo
if [[ "$fail" -eq 0 ]]; then
    echo "   PASS - the books balance and no event was posted twice."
else
    echo "   FAIL - ${fail} assertion(s). The ledger is not trustworthy."
fi
echo
exit "$fail"
