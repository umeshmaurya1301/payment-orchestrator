#!/usr/bin/env bash
#
# Phase 6f: does the retry ladder tier, does the DLQ catch what it cannot fix,
# and does the ledger converge after a replay?
#
#   tools/loadtest/retry-dlq.sh              # both arms, ~25 minutes
#   tools/loadtest/retry-dlq.sh transient    # arm 1 only
#   tools/loadtest/retry-dlq.sh poison       # arm 2 only
#
# TWO ARMS, AND THE SECOND ONE EXISTS BECAUSE THE FIRST CANNOT PROVE THE DLQ
#
# The phase says "inject 30% consumer failures". Taken literally - 30% of
# DELIVERIES fail, independently - the arithmetic is:
#
#     P(a message reaches the DLQ) = 0.3^4 = 0.0081
#
# because it has to lose four independent rolls: the first attempt and all three
# tiers. So a 30% run puts under 1% of messages in the DLQ, and a small batch
# puts NONE there.
#
# That is not a flaw in the injection, it is the entire value of the ladder, and
# it is the number this experiment exists to produce. But it does mean a run that
# only did arm 1 could report "the DLQ criterion passed" while the DLQ was empty
# and the replay path had never executed - the same false pass that wasted three
# alert drills in phase 4e and three failover drills in phase 5c.
#
#   ARM 1  transient, p=0.3    Where do failures actually land? Expect the ladder
#                              to absorb ~99% of them and the DLQ to be nearly
#                              empty. This is the measurement.
#
#   ARM 2  permanent, p=1.0    Every message fails every attempt, so every message
#                              walks all three tiers into the DLQ. This is the
#                              only way to exercise the DLQ, the replay endpoint
#                              and convergence with a batch small enough to watch.
#
# One fault at a time: the arms run in sequence, and the seam is disarmed between
# them.
#
# WHY THIS TAKES TWENTY-FIVE MINUTES
#
# Because the ladder is 5s + 1m + 10m and it is not being faked. A tier delay
# shortened for the convenience of the test would be measuring a different
# ladder than the one that ships, which is how a green test comes to describe
# software nobody is running.

set -uo pipefail

EDGE="${EDGE:-http://localhost:8080}"
LEDGER="${LEDGER:-http://localhost:8084}"
API_KEY="${API_KEY:-pk_test_dev_merchant_key}"
ARM="${1:-both}"

TRANSIENT_N="${TRANSIENT_N:-150}"
TRANSIENT_P="${TRANSIENT_P:-0.3}"
POISON_N="${POISON_N:-12}"

MAIN="payment.events"
T1="payment.events.retry-5000"
T2="payment.events.retry-60000"
T3="payment.events.retry-600000"
DLQ="payment.events.dlq"

FAIL=0

# --------------------------------------------------------------- plumbing ---

lq() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch_ledger -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

pq() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

kafka() {
    MSYS_NO_PATHCONV=1 docker exec payorch-kafka-1 "/opt/kafka/bin/$@"
}

offsets() {
    kafka kafka-get-offsets.sh --bootstrap-server kafka-1:9092 --topic "$1" 2>/dev/null \
        | tr -d '\r' | awk -F: '{s+=$3} END {print s+0}'
}

arm_seam() {
    curl -s -o /dev/null -X POST "${LEDGER}/actuator/chaosseams/ledger-consumer" \
        -H 'content-type: application/json' \
        -d "{\"action\":\"FAIL\",\"probability\":$1}"
    echo "   seam 'ledger-consumer' armed to FAIL with probability $1"
}

disarm_seam() {
    curl -s -o /dev/null -X DELETE "${LEDGER}/actuator/chaosseams"
}

injections() {
    curl -s "${LEDGER}/actuator/chaosseams" 2>/dev/null \
        | python -c "import json,sys; d=json.load(sys.stdin); print(d.get('injections',{}).get('ledger-consumer',0))" 2>/dev/null \
        || echo 0
}

dlq_pending() {
    curl -s "${LEDGER}/actuator/dlq?sample=1" 2>/dev/null \
        | python -c "import json,sys; print(json.load(sys.stdin)['pending'])" 2>/dev/null || echo "?"
}

outbox_pending() {
    pq "SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL;"
}

send() {
    local n="$1" tag="$2"
    for _ in $(seq 1 "$n"); do
        curl -s --max-time 30 -o /dev/null -X POST "${EDGE}/v1/payments" \
            -H "Content-Type: application/json" \
            -H "X-Api-Key: ${API_KEY}" \
            -H "Idempotency-Key: ${tag}-$(date +%s%N)-$RANDOM" \
            -d "{\"amountMinor\":4200,\"currency\":\"INR\",\"card\":{\"number\":\"4242424242424242\",\"expiryMonth\":12,\"expiryYear\":2030,\"cvv\":\"123\"},\"merchantReference\":\"${tag}\"}" &
        # Six at a time. Enough concurrency that the events spread across
        # partitions, few enough that the edge's rate limiters are not the thing
        # being measured.
        while [[ "$(jobs -r | wc -l)" -ge 6 ]]; do wait -n; done
    done
    wait
}

drain_outbox() {
    echo -n "   draining the outbox"
    for _ in $(seq 1 90); do
        [[ "$(outbox_pending)" == "0" ]] && { echo " done"; return 0; }
        echo -n "."
        sleep 2
    done
    echo " STILL PENDING: $(outbox_pending)"
}

ladder_row() {
    printf "   %-6s  %8s %8s %8s %8s %8s\n" "$1" \
        "$(offsets $MAIN)" "$(offsets $T1)" "$(offsets $T2)" "$(offsets $T3)" "$(offsets $DLQ)"
}

ladder_header() {
    echo
    printf "   %-6s  %8s %8s %8s %8s %8s\n" "t" "main" "5s" "1m" "10m" "DLQ"
    printf "   %-6s  %8s %8s %8s %8s %8s\n" "------" "--------" "--------" "--------" "--------" "--------"
}

# Watches the ladder until the DLQ stops moving and the tiers are empty of new
# arrivals, or the budget runs out. Prints a row every SAMPLE seconds.
watch_ladder() {
    local budget="$1" sample="${2:-20}"
    local elapsed=0 stable=0 last=""
    ladder_header
    ladder_row "0s"
    while [[ "$elapsed" -lt "$budget" ]]; do
        sleep "$sample"
        elapsed=$((elapsed + sample))
        ladder_row "${elapsed}s"
        local now; now="$(offsets $T1)-$(offsets $T2)-$(offsets $T3)-$(offsets $DLQ)"
        if [[ "$now" == "$last" ]]; then
            stable=$((stable + 1))
            # Three identical samples is only meaningful AFTER tier 3 could have
            # fired. Before that the ladder is legitimately quiet while a
            # 10-minute timer runs, and stopping there would report the run
            # finished when it had not started.
            [[ "$stable" -ge 3 && "$elapsed" -gt 700 ]] && { echo "   (settled)"; return 0; }
        else
            stable=0
        fi
        last="$now"
    done
}

chk() {
    if [[ "$2" == "$3" ]]; then
        printf "   ok   %-46s %s\n" "$1" "$2"
    else
        printf "   XX   %-46s %s (expected %s)\n" "$1" "$2" "$3"
        FAIL=$((FAIL+1))
    fi
}

chk_gt() {
    if [[ "${2:-0}" -gt "$3" ]]; then
        printf "   ok   %-46s %s\n" "$1" "$2"
    else
        printf "   XX   %-46s %s (expected > %s)\n" "$1" "$2" "$3"
        FAIL=$((FAIL+1))
    fi
}

# ------------------------------------------------------------ preflight -----

echo "=============================================================="
echo " PREFLIGHT"
echo "=============================================================="

publisher="$(docker inspect payorch-payment-orchestrator \
    --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
    | grep '^EVENTS_PUBLISHER=' | cut -d= -f2)"
if [[ "${publisher}" != "outbox" ]]; then
    echo "refusing to run: needs EVENTS_PUBLISHER=outbox (found '${publisher:-unset}')." >&2
    exit 2
fi

# The topics must EXIST and be replicated. autoCreateTopics=false in the consumer
# means a missing one is a startup failure rather than a silent RF=1 topic - but
# checking here too means the experiment says so in one line instead of the
# operator reading a stack trace.
missing=0
for t in "$MAIN" "$T1" "$T2" "$T3" "$DLQ"; do
    desc="$(kafka kafka-topics.sh --bootstrap-server kafka-1:9092 --describe --topic "$t" 2>/dev/null | head -1)"
    if [[ -z "$desc" ]]; then
        echo "   MISSING topic ${t} - run tools/kafka/topics.sh create" >&2
        missing=1
        continue
    fi
    rf="$(echo "$desc" | grep -o "ReplicationFactor: [0-9]*" | awk '{print $2}')"
    isr="$(echo "$desc" | grep -c "min.insync.replicas=2")"
    printf "   %-30s RF=%s  min.insync.replicas=%s\n" "$t" "${rf:-?}" \
        "$([[ "$isr" -ge 1 ]] && echo yes || echo NO)"
    [[ "${rf:-0}" -lt 3 ]] && { echo "   ^ RF < 3. This is the auto-creation trap." >&2; missing=1; }
done
[[ "$missing" -eq 1 ]] && exit 2

if ! curl -s "${LEDGER}/actuator/dlq?sample=1" | grep -q '"pending"'; then
    echo "refusing to run: ${LEDGER}/actuator/dlq is not answering." >&2
    echo "The replay path is half of what this experiment measures." >&2
    exit 2
fi

disarm_seam
echo "   seams disarmed"

E0="$(lq 'SELECT COUNT(DISTINCT event_id) FROM ledger_entry;')"
D0="$(offsets $DLQ)"
echo "   ledger has ${E0} events posted, DLQ holds ${D0} records"

# ------------------------------------------------------------- arm 1 --------

if [[ "$ARM" == "both" || "$ARM" == "transient" ]]; then
    echo
    echo "=============================================================="
    echo " ARM 1 - TRANSIENT FAILURES, p=${TRANSIENT_P}, ${TRANSIENT_N} payments"
    echo "=============================================================="
    echo "   Hypothesis: the ladder absorbs almost everything. 0.3^4 = 0.81% of"
    echo "   messages should reach the DLQ, so expect 0 or 1 out of ${TRANSIENT_N}."
    echo

    A1_MAIN0="$(offsets $MAIN)"; A1_T10="$(offsets $T1)"; A1_T20="$(offsets $T2)"
    A1_T30="$(offsets $T3)"; A1_DLQ0="$(offsets $DLQ)"; A1_INJ0="$(injections)"

    arm_seam "$TRANSIENT_P"
    send "$TRANSIENT_N" "retry-transient"
    drain_outbox
    watch_ladder 780 30
    disarm_seam

    A1_INJ=$(( $(injections) - A1_INJ0 ))
    A1_MAIN=$(( $(offsets $MAIN) - A1_MAIN0 ))
    A1_T1=$(( $(offsets $T1) - A1_T10 ))
    A1_T2=$(( $(offsets $T2) - A1_T20 ))
    A1_T3=$(( $(offsets $T3) - A1_T30 ))
    A1_DLQ=$(( $(offsets $DLQ) - A1_DLQ0 ))

    echo
    echo "   WHERE THE FAILURES LANDED"
    printf "   events published            %6d\n" "$A1_MAIN"
    printf "   chaos injections            %6s\n" "$A1_INJ"
    printf "   entered tier 1 (5s)         %6d\n" "$A1_T1"
    printf "   entered tier 2 (1m)         %6d\n" "$A1_T2"
    printf "   entered tier 3 (10m)        %6d\n" "$A1_T3"
    printf "   reached the DLQ             %6d\n" "$A1_DLQ"
    if [[ "$A1_MAIN" -gt 0 ]]; then
        python - "$A1_T1" "$A1_MAIN" "$A1_DLQ" <<'PY'
import sys
t1, main, dlq = (int(x) for x in sys.argv[1:4])
print(f"   ladder absorbed             {100*(t1-dlq)/t1:5.1f}% of failures" if t1 else "   no failures entered the ladder")
print(f"   DLQ rate                    {100*dlq/main:5.2f}% of messages")
PY
    fi
    echo
    chk_gt "tier 1 received messages" "$A1_T1" 0
    chk_gt "the seam actually fired" "$A1_INJ" 0
fi

# ------------------------------------------------------------- arm 2 --------

if [[ "$ARM" == "both" || "$ARM" == "poison" ]]; then
    echo
    echo "=============================================================="
    echo " ARM 2 - PERMANENT FAILURE, p=1.0, ${POISON_N} payments"
    echo "=============================================================="
    echo "   Hypothesis: every message walks 5s, 1m and 10m and lands in the DLQ."
    echo "   Nothing is dropped, nothing is posted, and the ledger is short by"
    echo "   exactly ${POISON_N} events until the replay."
    echo

    A2_MAIN0="$(offsets $MAIN)"; A2_T10="$(offsets $T1)"; A2_T20="$(offsets $T2)"
    A2_T30="$(offsets $T3)"; A2_DLQ0="$(offsets $DLQ)"
    A2_LEDGER0="$(lq 'SELECT COUNT(DISTINCT event_id) FROM ledger_entry;')"

    arm_seam "1.0"
    send "$POISON_N" "retry-poison"
    drain_outbox
    watch_ladder 900 30

    A2_MAIN=$(( $(offsets $MAIN) - A2_MAIN0 ))
    A2_T1=$(( $(offsets $T1) - A2_T10 ))
    A2_T2=$(( $(offsets $T2) - A2_T20 ))
    A2_T3=$(( $(offsets $T3) - A2_T30 ))
    A2_DLQ=$(( $(offsets $DLQ) - A2_DLQ0 ))
    A2_LEDGER=$(( $(lq 'SELECT COUNT(DISTINCT event_id) FROM ledger_entry;') - A2_LEDGER0 ))

    echo
    echo "   THE FULL LADDER"
    printf "   events published            %6d\n" "$A2_MAIN"
    printf "   entered tier 1 (5s)         %6d\n" "$A2_T1"
    printf "   entered tier 2 (1m)         %6d\n" "$A2_T2"
    printf "   entered tier 3 (10m)        %6d\n" "$A2_T3"
    printf "   reached the DLQ             %6d\n" "$A2_DLQ"
    printf "   posted to the ledger        %6d\n" "$A2_LEDGER"
    echo

    chk "every message walked tier 1"        "$A2_T1"  "$A2_MAIN"
    chk "every message walked tier 2"        "$A2_T2"  "$A2_MAIN"
    chk "every message walked tier 3"        "$A2_T3"  "$A2_MAIN"
    chk "every message reached the DLQ"      "$A2_DLQ" "$A2_MAIN"
    chk "nothing was posted while failing"   "$A2_LEDGER" "0"

    # ---- forensics -------------------------------------------------------
    echo
    echo "   WHAT A DLQ RECORD CARRIES"
    SAMPLE="$(curl -s "${LEDGER}/actuator/dlq?sample=2")"
    echo "$SAMPLE" | python -c "
import json,sys
d = json.load(sys.stdin)
print(f\"     pending={d['pending']}  records={d['records']}  partitions={d['partitions']}\")
for r in d.get('sample', [])[:2]:
    print(f\"     - offset {r['partition']}/{r['offset']}  attempts={r.get('attempts')}\")
    print(f\"       from      {r.get('originalTopic')}\")
    print(f\"       exception {r.get('exception')}\")
    print(f\"       message   {str(r.get('exceptionMessage'))[:70]}\")
" 2>/dev/null || echo "     (could not read /actuator/dlq)"

    # ASSERTED, not just printed. The first passing run of this experiment had
    # every forensic field blank - the code read the kafka_dlt-* header family
    # while the retry machinery writes the plain one - and every assertion above
    # still passed, because depth is right whether or not the record says
    # anything about itself. A DLQ with no forensics is a list of payloads
    # somebody has to guess about.
    FORENSICS="$(echo "$SAMPLE" | python -c "
import json,sys
d = json.load(sys.stdin)
s = d.get('sample', [])
ok = sum(1 for r in s
         if r.get('originalTopic') and r.get('exception') and (r.get('attempts') or -1) > 0)
print(ok)
" 2>/dev/null || echo 0)"
    chk_gt "DLQ records carry their forensics" "$FORENSICS" 0

    # ---- the PII check ---------------------------------------------------
    #
    # Phase 6's rule: mask at PRODUCE time, because DLQ messages persist longest
    # and are read by humans. Asserting it here rather than trusting the record
    # definition - a field added to PaymentEventMessage six months from now will
    # not re-read that comment.
    echo
    echo "   PII IN THE DLQ"
    PAYLOADS="$(curl -s "${LEDGER}/actuator/dlq?sample=20" \
        | python -c "import json,sys; print('\n'.join(str(r.get('payload')) for r in json.load(sys.stdin).get('sample',[])))" 2>/dev/null)"
    PANS="$(echo "$PAYLOADS" | grep -oE '[0-9]{13,19}' | wc -l | tr -d ' ')"
    CVVS="$(echo "$PAYLOADS" | grep -ciE '"cvv"|"expiry|"pan"' | tr -d ' ')"
    chk "card numbers in DLQ payloads"  "$PANS" "0"
    chk "cvv/expiry/pan fields in DLQ"  "$CVVS" "0"

    # ---- replay ----------------------------------------------------------
    echo
    echo "=============================================================="
    echo " REPLAY"
    echo "=============================================================="
    disarm_seam
    echo "   seam disarmed - the cause is fixed, now recover the messages"
    sleep 2
    REPLAY="$(curl -s -X POST "${LEDGER}/actuator/dlq" \
        -H 'content-type: application/json' -d '{"limit":1000}')"
    echo "$REPLAY" | python -c "
import json,sys
d = json.load(sys.stdin)
print(f\"   replayed {d['replayed']} record(s) to {d['target']} in {d['tookMs']}ms, {d['failed']} failed\")
for k, v in d.get('byOriginalTopic', {}).items():
    print(f'     from {k}: {v}')
" 2>/dev/null || echo "   $REPLAY"

    echo -n "   waiting for the ledger to catch up"
    for _ in $(seq 1 40); do
        now="$(lq 'SELECT COUNT(DISTINCT event_id) FROM ledger_entry;')"
        [[ $((now - A2_LEDGER0)) -ge "$A2_MAIN" ]] && break
        echo -n "."
        sleep 3
    done
    echo

    A2_AFTER=$(( $(lq 'SELECT COUNT(DISTINCT event_id) FROM ledger_entry;') - A2_LEDGER0 ))
    chk "events recovered by the replay" "$A2_AFTER" "$A2_MAIN"
    chk "DLQ has nothing left pending"   "$(dlq_pending)" "0"
fi

# ------------------------------------------------------------ convergence ---

echo
echo "=============================================================="
echo " CONVERGENCE"
echo "=============================================================="
disarm_seam

IMBALANCE="$(lq 'SELECT COALESCE(SUM(amount_minor),0) FROM ledger_entry;')"
OVERPOSTED="$(lq 'SELECT COUNT(*) FROM (SELECT event_id FROM ledger_entry GROUP BY event_id HAVING COUNT(*) > 2) x;')"
BALSUM="$(lq 'SELECT COALESCE(SUM(balance_minor),0) FROM ledger_account;')"

chk "double-entry invariant (sum=0)"     "$IMBALANCE"  "0"
chk "events posted more than once"       "$OVERPOSTED" "0"
chk "balances sum to zero"               "$BALSUM"     "0"

echo
echo "=============================================================="
if [[ "$FAIL" -eq 0 ]]; then
    echo " PASS - the ladder tiers, the DLQ catches, the replay recovers, and"
    echo "        the books balance after all of it."
else
    echo " FAIL - ${FAIL} assertion(s)."
fi
echo "=============================================================="
echo
exit "$FAIL"
