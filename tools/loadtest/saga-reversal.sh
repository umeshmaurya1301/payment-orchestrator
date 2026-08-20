#!/usr/bin/env bash
#
# Phase 6k: when a capture succeeds at the provider and the ledger cannot record
# it, does the saga give the money back - and does it stay given back?
#
#   tools/loadtest/saga-reversal.sh              # all three arms, ~30 minutes
#   tools/loadtest/saga-reversal.sh compensate   # arm A only
#   tools/loadtest/saga-reversal.sh replay       # arm B only (needs A first)
#   tools/loadtest/saga-reversal.sh guard        # arm C only
#
# WHAT 6J LEFT OPEN
#
# Experiment 15 arm C measured the gap and stopped there: the provider takes the
# money, the ledger cannot post, and BOTH of this system's invariants stay green
# while the books are wrong about the world. SUM(amount_minor) is zero because
# the missing pair is missing in balanced halves, and drift is zero because the
# cached balances agree with the entries that do exist. Nothing in the ledger can
# see it. That is what a saga is for.
#
# THE THREE ARMS, AND WHY THE THIRD IS NOT OPTIONAL
#
#   A  compensate   Capture with the ledger seam armed permanently. Every capture
#                   event fails all four attempts, lands in the DLQ, and should
#                   produce a compensation that reverses it at the provider and
#                   moves the payment to REVERSED. Measures the whole path.
#
#   B  replay       Then replay the DLQ - the phase-6f feature, used exactly as
#                   an operator would. The capture events it pushes back through
#                   have never been posted, so every idempotency defence in the
#                   ledger says "go ahead". Only the tombstone stops them. This
#                   arm is the difference between a compensation and a
#                   compensation that survives somebody trying to help.
#
#   C  guard        The failure mode the compensation could CAUSE. A capture can
#                   reach the DLQ with its ledger entries already posted - the
#                   write succeeds and the webhook dispatch then fails four times
#                   because a merchant's endpoint is down. The books are complete
#                   and the merchant simply has not been told. Reversing on that
#                   basis would take back money the ledger says is owed, to fix
#                   somebody else's HTTP 502. Arm C is the assertion that this
#                   does not happen, and without it arm A's green result says
#                   only "the machinery fires", not "it fires when it should".
#
# WHY THIS TAKES THIRTY MINUTES
#
# Because the ladder is 5s + 1m + 10m and it is not being faked, and arms A and C
# each have to walk it. A tier delay shortened for the convenience of the test
# would measure a different ladder than the one that ships.

set -uo pipefail

EDGE="${EDGE:-http://localhost:8080}"
LEDGER="${LEDGER:-http://localhost:8084}"
ORCH="${ORCH:-http://localhost:8081}"
API_KEY="${API_KEY:-pk_test_dev_merchant_key}"
ARM="${1:-both}"

N="${N:-8}"

DLQ="payment.events.dlq"
COMP="payment.compensation"
COMP_DLQ="payment.compensation.dlq"

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

jget() {
    python -c "import json,sys
d=json.load(sys.stdin)
for k in '$1'.split('.'):
    d = d[k]
print(d)" 2>/dev/null || echo "?"
}

ledger_field() {
    curl -s "${LEDGER}/actuator/ledger" 2>/dev/null | jget "$1"
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

authorize_payment() {
    curl -s --max-time 30 -X POST "${EDGE}/v1/payments" \
        -H "Content-Type: application/json" \
        -H "X-Api-Key: ${API_KEY}" \
        -H "Idempotency-Key: saga-$(date +%s%N)-$RANDOM" \
        -d '{"amountMinor":4200,"currency":"INR","card":{"number":"4242424242424242","expiryMonth":12,"expiryYear":2030,"cvv":"123"},"merchantReference":"saga-reversal"}' \
        | jget id
}

capture_payment() {
    curl -s --max-time 30 -o /dev/null -w '%{http_code}' \
        -X POST "${EDGE}/v1/payments/$1/capture" -H "X-Api-Key: ${API_KEY}"
}

state_of() {
    pq "SELECT state FROM payment WHERE id = UNHEX(REPLACE('$1','-',''));"
}

# Legs of one entry_type for one payment. The whole arm turns on which PAIR was
# posted, so the assertions name the entry types rather than counting rows.
legs_for() {
    lq "SELECT COUNT(*) FROM ledger_entry
        WHERE payment_id = UNHEX(REPLACE('$1','-','')) AND entry_type = '$2';"
}

entries_for() {
    lq "SELECT COUNT(*) FROM ledger_entry WHERE payment_id = UNHEX(REPLACE('$1','-',''));"
}

# What this payment did to the books, all accounts together. Zero means the
# authorization and its reversal cancelled and the payment left no trace on any
# balance - which is the correct end state for money that went out and came back.
net_for() {
    lq "SELECT COALESCE(SUM(amount_minor),0) FROM ledger_entry
        WHERE payment_id = UNHEX(REPLACE('$1','-',''));"
}

tombstoned() {
    lq "SELECT COUNT(*) FROM reversed_capture
        WHERE payment_id = UNHEX(REPLACE('$1','-',''));"
}

outbox_pending() {
    pq "SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL;"
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

# Waits for the ladder to give up and the saga to answer. Both, in one loop,
# because the interesting failure is the ladder finishing and nothing happening.
wait_for_compensation() {
    local want="$1" budget="${2:-900}" elapsed=0
    printf "\n   %-8s %8s %8s %8s %8s\n" "t" "DLQ" "comp" "failed" "reversed"
    printf "   %-8s %8s %8s %8s %8s\n" "--------" "--------" "--------" "--------" "--------"
    while [[ "$elapsed" -lt "$budget" ]]; do
        local d c f r
        d="$(offsets $DLQ)"
        c="$(ledger_field compensation.requested)"
        f="$(ledger_field compensation.failed)"
        r="$(pq "SELECT COUNT(*) FROM payment WHERE state = 'REVERSED';")"
        printf "   %-8s %8s %8s %8s %8s\n" "${elapsed}s" "$d" "$c" "$f" "$r"
        [[ "${r:-0}" -ge "$want" ]] && { echo "   (settled)"; return 0; }
        sleep 30
        elapsed=$((elapsed + 30))
    done
    echo "   (budget exhausted)"
}

chk() {
    if [[ "$2" == "$3" ]]; then
        printf "   ok   %-52s %s\n" "$1" "$2"
    else
        printf "   XX   %-52s %s (expected %s)\n" "$1" "$2" "$3"
        FAIL=$((FAIL+1))
    fi
}

chk_gt() {
    if [[ "${2:-0}" -gt "$3" ]]; then
        printf "   ok   %-52s %s\n" "$1" "$2"
    else
        printf "   XX   %-52s %s (expected > %s)\n" "$1" "$2" "$3"
        FAIL=$((FAIL+1))
    fi
}

env_of() {
    docker inspect "payorch-$1" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
        | grep "^$2=" | cut -d= -f2
}

# ------------------------------------------------------------ preflight -----

echo "=============================================================="
echo " PREFLIGHT"
echo "=============================================================="

publisher="$(env_of payment-orchestrator EVENTS_PUBLISHER)"
if [[ "${publisher}" != "outbox" ]]; then
    echo "refusing to run: needs EVENTS_PUBLISHER=outbox (found '${publisher:-unset}')." >&2
    exit 2
fi

# The one that makes this experiment different from every earlier one: the
# orchestrator has to be CONSUMING. With this off the ledger still publishes
# compensation requests and nothing acts on them, which would look exactly like
# a saga that does not work.
compensation="$(env_of payment-orchestrator COMPENSATION_ENABLED)"
if [[ "${compensation}" != "true" ]]; then
    echo "refusing to run: needs COMPENSATION_ENABLED=true on the orchestrator" >&2
    echo "(found '${compensation:-unset}'). Without it the requests pile up unread," >&2
    echo "and the run would report a broken saga rather than a disabled one." >&2
    exit 2
fi

missing=0
for t in "$DLQ" "$COMP" "$COMP_DLQ"; do
    desc="$(kafka kafka-topics.sh --bootstrap-server kafka-1:9092 --describe --topic "$t" 2>/dev/null | head -1)"
    if [[ -z "$desc" ]]; then
        echo "   MISSING topic ${t} - run tools/kafka/topics.sh create" >&2
        missing=1
        continue
    fi
    rf="$(echo "$desc" | grep -o "ReplicationFactor: [0-9]*" | awk '{print $2}')"
    printf "   %-30s RF=%s\n" "$t" "${rf:-?}"
    [[ "${rf:-0}" -lt 3 ]] && { echo "   ^ RF < 3. This is the auto-creation trap." >&2; missing=1; }
done
[[ "$missing" -eq 1 ]] && exit 2

if ! curl -s "${LEDGER}/actuator/ledger" | grep -q '"compensation"'; then
    echo "refusing to run: ${LEDGER}/actuator/ledger has no compensation block." >&2
    echo "The ledger is running a build from before 6k." >&2
    exit 2
fi

disarm_seam
echo "   seams disarmed"

D0="$(ledger_field books.driftedAccounts)"
chk "no pre-existing drift" "${D0}" "0"

# Baselined rather than assumed zero: this may not be the first run against this
# cluster, and an assertion that only passes on a fresh broker is one that gets
# quietly disabled the second time somebody uses it.
CDLQ0="$(offsets $COMP_DLQ)"

# --------------------------------------------------------------- arm A ------

declare -a IDS=()

if [[ "$ARM" == "both" || "$ARM" == "compensate" || "$ARM" == "replay" ]]; then
    echo
    echo "=============================================================="
    echo " ARM A - THE CAPTURE THE LEDGER CANNOT RECORD, ${N} payments"
    echo "=============================================================="
    echo "   Hypothesis: every capture succeeds at the provider, every capture"
    echo "   event walks the ladder into the DLQ, and every one produces a"
    echo "   compensation that reverses it. Each payment should end REVERSED,"
    echo "   with four legs - authorize's pair and the reversal's - netting to"
    echo "   zero across all three accounts. The capture pair should NOT exist."
    echo

    # Authorize first, with the seam DISARMED. The authorization has to reach
    # the ledger or there is nothing for the reversal to cancel, and a run where
    # both events were lost would net to zero for the wrong reason.
    for _ in $(seq 1 "$N"); do
        IDS+=("$(authorize_payment)")
    done
    drain_outbox
    echo "   ${#IDS[@]} payments authorized and posted"
    chk "authorization posted its pair" "$(legs_for "${IDS[0]}" MERCHANT_CREDIT)" "1"

    # NOW arm it, permanently. p=1.0 rather than 0.3 for experiment 11's reason:
    # at 0.3 a message reaches the DLQ 0.81% of the time, so a batch this size
    # would produce no compensations at all and the arm would pass by measuring
    # nothing.
    arm_seam 1.0

    captured=0
    for id in "${IDS[@]}"; do
        [[ "$(capture_payment "${id}")" == "200" ]] && captured=$((captured+1))
    done
    chk "every capture succeeded at the provider" "${captured}" "${N}"
    drain_outbox

    wait_for_compensation "$N" 900
    disarm_seam

    reversed=0
    for id in "${IDS[@]}"; do
        [[ "$(state_of "${id}")" == "REVERSED" ]] && reversed=$((reversed+1))
    done

    echo
    chk "every payment reached REVERSED" "${reversed}" "${N}"
    chk "compensations requested" "$(ledger_field compensation.requested)" "${N}"
    chk "compensations that could not be published" "$(ledger_field compensation.failed)" "0"
    chk "compensations correctly skipped" "$(ledger_field compensation.skipped)" "0"
    chk "tombstones written" "$(ledger_field compensation.reversedCaptures)" "${N}"
    # Anything here is a compensation the PROVIDER refused - an unresolved
    # disagreement about real money, sitting where a person will find it. Zero
    # is the only passing value, and it is a different zero from the one above:
    # `failed` means the request never left this service, this means it arrived
    # and could not be carried out.
    chk "compensations the provider refused" "$(( $(offsets $COMP_DLQ) - CDLQ0 ))" "0"

    echo
    echo "   per payment:"
    chk "  the capture pair was never posted" "$(legs_for "${IDS[0]}" CLEARING_CREDIT)" "0"
    chk "  the reversal debited the merchant" "$(legs_for "${IDS[0]}" MERCHANT_REVERSAL)" "1"
    chk "  the reversal credited clearing" "$(legs_for "${IDS[0]}" CLEARING_REVERSAL)" "1"
    chk "  four legs in total" "$(entries_for "${IDS[0]}")" "4"
    # THE HEADLINE NUMBER. Zero across every account this payment touched: the
    # merchant is owed nothing, clearing carries no liability, and the network
    # was never involved because the capture never posted.
    chk "  the payment nets to zero across all accounts" "$(net_for "${IDS[0]}")" "0"

    chk "the books still balance" "$(ledger_field books.imbalance)" "0"
    chk "no account has drifted" "$(ledger_field books.driftedAccounts)" "0"
fi

# --------------------------------------------------------------- arm B ------

if [[ "$ARM" == "both" || "$ARM" == "replay" ]]; then
    echo
    echo "=============================================================="
    echo " ARM B - THE OPERATOR REPLAYS THE DLQ AFTERWARDS"
    echo "=============================================================="
    echo "   Hypothesis: nothing happens, and that takes a tombstone to achieve."
    echo "   These capture events have never been posted, so existsByEventId is"
    echo "   false and the unique constraint would accept both legs. The replay"
    echo "   tool has no idea the payments were compensated days ago."
    echo

    before_legs="$(entries_for "${IDS[0]}")"
    before_net="$(net_for "${IDS[0]}")"

    replayed="$(curl -s -X POST "${LEDGER}/actuator/dlq" \
        -H 'content-type: application/json' -d '{"limit":500}' | jget replayed)"
    echo "   replayed ${replayed} records from the DLQ"
    sleep 30

    chk "the replay posted no new legs" "$(entries_for "${IDS[0]}")" "${before_legs}"
    chk "the payment still nets to zero" "$(net_for "${IDS[0]}")" "${before_net}"
    chk "still no capture pair" "$(legs_for "${IDS[0]}" CLEARING_CREDIT)" "0"
    chk "the payment is still REVERSED" "$(state_of "${IDS[0]}")" "REVERSED"
    chk "no account has drifted" "$(ledger_field books.driftedAccounts)" "0"

    # Not a compensation - the replayed capture is IGNORED by the tombstone
    # before it can fail, so it never reaches the DLT handler at all.
    chk "the replay requested no new compensations" \
        "$(ledger_field compensation.requested)" "${N}"
fi

# --------------------------------------------------------------- arm C ------

if [[ "$ARM" == "both" || "$ARM" == "guard" ]]; then
    echo
    echo "=============================================================="
    echo " ARM C - A DEAD-LETTERED CAPTURE THAT WAS ALREADY POSTED"
    echo "=============================================================="

    webhooks="$(env_of ledger-notifier WEBHOOKS_ENABLED)"
    if [[ "${webhooks}" != "true" ]]; then
        echo "   SKIPPED: needs WEBHOOKS_ENABLED=true on ledger-notifier and a sink"
        echo "   that refuses deliveries. Without it there is no way to dead-letter"
        echo "   a capture whose ledger write SUCCEEDED, which is the only case this"
        echo "   arm is about. Re-run with the webhook sink stopped:"
        echo
        echo "     docker compose --profile async stop webhook-sink"
        echo "     WEBHOOKS_ENABLED=true tools/loadtest/saga-reversal.sh guard"
    else
        echo "   Hypothesis: the ledger posts the capture, the webhook fails four"
        echo "   times, the record dead-letters - and NO compensation is requested,"
        echo "   because the books are complete and the failure is downstream."
        echo "   Reversing here would be the compensation causing the incident."
        echo

        skipped0="$(ledger_field compensation.skipped)"
        requested0="$(ledger_field compensation.requested)"

        gid="$(authorize_payment)"
        drain_outbox
        capture_payment "${gid}" >/dev/null
        drain_outbox

        echo "   waiting out the ladder (5s + 1m + 10m)"
        sleep 780

        chk "the capture pair WAS posted" "$(legs_for "${gid}" CLEARING_CREDIT)" "1"
        chk "the guard skipped it" \
            "$(( $(ledger_field compensation.skipped) - skipped0 ))" "1"
        chk "no compensation was requested" \
            "$(( $(ledger_field compensation.requested) - requested0 ))" "0"
        chk "the payment is still CAPTURED" "$(state_of "${gid}")" "CAPTURED"
    fi
fi

# ---------------------------------------------------------------- verdict ---

echo
echo "=============================================================="
if [[ "$FAIL" -eq 0 ]]; then
    echo " PASS"
else
    echo " ${FAIL} ASSERTION(S) FAILED"
fi
echo "=============================================================="
exit $((FAIL > 0))
