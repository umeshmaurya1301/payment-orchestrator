#!/usr/bin/env bash
#
# Phase 6i: do the async alerts fire, and do they fire for the RIGHT reasons?
#
#   tools/obs/async-alert-drill.sh              # all three arms, ~45 minutes
#   tools/obs/async-alert-drill.sh lag          # arm 1 only
#   tools/obs/async-alert-drill.sh dead         # arm 2 only
#   tools/obs/async-alert-drill.sh dlq          # arm 3 only
#
# Phase 6's last exit criterion:
#
#     "Alerts on consumer lag and DLQ depth fire during the chaos run"
#
# And phase 4's rule about what that sentence is worth: an alert that has never
# fired is a hypothesis. Experiment 07 found THREE OF FOUR phase-4 rules could
# not fire at all - one queried a counter registered as a gauge, one thresholded
# a condition four nested limiters made unreachable, one measured HTTP 5xx while
# 99.7% of payments failed with a 201. All four looked correct in the UI.
#
# THREE ARMS, AND THE SECOND IS THE ONE THAT MATTERS
#
#   1  SLOW CONSUMER      the ledger is alive and falling behind. Lag climbs,
#                         the threshold is crossed, the rule fires. This is the
#                         arm anyone would write.
#
#   2  WEDGED CONSUMER    the ledger is FROZEN (SIGSTOP) while events keep
#                         arriving. This is the WORSE incident and it is the one a
#                         lag threshold cannot catch: records-lag-max is computed by the
#                         consumer, so a consumer that is gone reports no lag,
#                         and `> 50` on a series that does not exist is never
#                         true. The rule has to fire on SILENCE - alertOnAbsent -
#                         and this arm is the only thing that proves it does.
#
#   3  DEAD LETTERS       records in the DLQ that nobody has replayed. Fires on
#                         `pending`, deliberately not on record count: a
#                         log-structured topic never gives records back, so a
#                         rule on depth fires during the first incident and stays
#                         firing for the life of the cluster.
#
# WHY THIS TAKES FORTY-FIVE MINUTES
#
# Because the alerting pipeline's own latency is about eight minutes and is not
# advertised: evalWindow (2-5m) plus SigNoz's eval_delay (2m), which nothing in
# the UI mentions. tools/obs/alert-drill.sh has the full measurement and the
# story of the drill that concluded "did not resolve" twenty-two seconds early.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
UI="${SIGNOZ_UI:-http://localhost:3301}"
EDGE="${EDGE:-http://localhost:8080}"
LEDGER="${LEDGER:-http://localhost:8084}"
API_KEY="${API_KEY:-pk_test_dev_merchant_key}"
ARM="${1:-all}"

LAG_PAUSE_MS="${LAG_PAUSE_MS:-3000}"
LAG_N="${LAG_N:-250}"
DEAD_N="${DEAD_N:-40}"
DLQ_N="${DLQ_N:-5}"

# Long enough to outlast evalWindow + eval_delay. Shortening these is how a
# drill reports a working alert as broken.
FIRE_BUDGET="${FIRE_BUDGET:-420}"
RESOLVE_BUDGET="${RESOLVE_BUDGET:-660}"

FAIL=0

# --- SigNoz access (see signoz.sh for why it looks like this) ---------------

org_id() {
    docker exec signoz-metastore-postgres-0 \
        psql -U signoz -d signoz -t -A -c "select id from organizations limit 1" 2>/dev/null | tr -d '\r\n'
}

TOKEN="$(curl -s -X POST "${UI}/api/v2/sessions/email_password" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${SIGNOZ_ADMIN_EMAIL:-admin@payorch.local}\",\"password\":\"${SIGNOZ_ADMIN_PASSWORD:-Payorch!Local1}\",\"orgID\":\"$(org_id)\"}" \
    | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null | tr -d '\r\n')"

if [[ -z "${TOKEN}" ]]; then
    echo "could not authenticate to ${UI} - is SigNoz up? (tools/obs/signoz.sh up)" >&2
    exit 2
fi

rule_state() {
    curl -s -H "Authorization: Bearer ${TOKEN}" "${UI}/api/v1/rules" | python -c "
import sys,json
for r in json.load(sys.stdin).get('data',{}).get('rules',[]):
    if r.get('alert') == '$1':
        print(r.get('state') or 'inactive')
        break
else:
    print('missing')
" 2>/dev/null
}

# --- the stack --------------------------------------------------------------

kafka() {
    MSYS_NO_PATHCONV=1 docker exec payorch-kafka-1 "/opt/kafka/bin/$@"
}

scrape() {
    curl -s --max-time 5 "${LEDGER}/actuator/prometheus" 2>/dev/null
}

payorch_lag() {
    scrape | grep '^payorch_consumer_lag{' | awk '{print int($NF)}' | head -1
}

client_lag_series() {
    scrape | grep -c '^kafka_consumer_fetch_manager_records_lag_max{' | tr -d ' '
}

dlq_pending() {
    scrape | grep '^payorch_dlq_pending{' | awk '{print int($NF)}' | head -1
}

arm_pause() {
    curl -s -o /dev/null -X POST "${LEDGER}/actuator/chaosseams/ledger-consumer" \
        -H 'content-type: application/json' \
        -d "{\"action\":\"PAUSE\",\"pauseMs\":$1,\"probability\":1.0}"
}

disarm() {
    curl -s -o /dev/null -X DELETE "${LEDGER}/actuator/chaosseams" 2>/dev/null
}

send() {
    local n="$1"
    for _ in $(seq 1 "$n"); do
        curl -s --max-time 30 -o /dev/null -X POST "${EDGE}/v1/payments" \
            -H "Content-Type: application/json" \
            -H "X-Api-Key: ${API_KEY}" \
            -H "Idempotency-Key: drill-$(date +%s%N)-$RANDOM" \
            -d '{"amountMinor":4200,"currency":"INR","card":{"number":"4242424242424242","expiryMonth":12,"expiryYear":2030,"cvv":"123"},"merchantReference":"async-alert-drill"}' &
        while [[ "$(jobs -r | wc -l)" -ge 6 ]]; do wait -n; done
    done
    wait
}

cleanup() {
    disarm >/dev/null 2>&1
    # Unconditionally, and ignoring the error when it was never paused. A drill
    # that dies mid-arm must not leave the ledger frozen.
    docker unpause payorch-ledger-notifier >/dev/null 2>&1
    docker start payorch-ledger-notifier >/dev/null 2>&1
}
trap cleanup EXIT

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

# Waits for a rule to leave `inactive`, printing the metric as it goes so a
# failure says WHY - "the alert did not fire" and "the condition never held" are
# different problems and only one of them is the alert's fault.
wait_for_fire() {
    local rule="$1" probe="$2" budget="$3" elapsed=0
    echo "   waiting for '${rule}' to fire (budget ${budget}s)"
    while [[ "${elapsed}" -lt "${budget}" ]]; do
        local state; state="$(rule_state "${rule}")"
        printf "     [%4ss] %-14s %s\n" "${elapsed}" "${state}" "$(${probe})"
        [[ "${state}" != "inactive" && "${state}" != "missing" ]] && return 0
        sleep 30
        elapsed=$((elapsed + 30))
    done
    return 1
}

wait_for_resolve() {
    local rule="$1" probe="$2" budget="$3" elapsed=0
    echo "   waiting for '${rule}' to resolve (budget ${budget}s)"
    while [[ "${elapsed}" -lt "${budget}" ]]; do
        local state; state="$(rule_state "${rule}")"
        printf "     [%4ss] %-14s %s\n" "${elapsed}" "${state}" "$(${probe})"
        [[ "${state}" == "inactive" ]] && return 0
        sleep 30
        elapsed=$((elapsed + 30))
    done
    return 1
}

# ------------------------------------------------------------- preflight ----

echo "=============================================================="
echo " PREFLIGHT"
echo "=============================================================="

publisher="$(docker inspect payorch-payment-orchestrator \
    --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
    | grep '^EVENTS_PUBLISHER=' | cut -d= -f2)"
if [[ "${publisher}" != "outbox" ]]; then
    echo "   EVENTS_PUBLISHER is '${publisher:-unset}', not 'outbox' - no events," >&2
    echo "   so no lag to alert on." >&2
    exit 2
fi
echo "   publisher                      outbox"

for rule in consumer-lag dlq-pending; do
    state="$(rule_state "${rule}")"
    if [[ "${state}" == "missing" ]]; then
        echo "   rule '${rule}' is not registered - run tools/obs/signoz.sh apply" >&2
        exit 2
    fi
    printf "   rule %-26s %s\n" "${rule}" "${state}"
done

# BOTH RULES MUST BE QUIET BEFORE THE DRILL STARTS, and the first run of this
# script is why the check exists. consumer-lag was already `firing` in
# preflight - correctly, on alertOnAbsent, because the metric had been deployed
# four minutes earlier and everything before that was no-data. Arm 1 would then
# have "passed" the instant it looked, having proved nothing at all.
#
# Same family as experiment 07's false passes and 5c's three green failover
# drills: a drill that does not establish its own baseline is measuring the
# state it inherited rather than the state it created.
for rule in consumer-lag dlq-pending; do
    elapsed=0
    while [[ "$(rule_state "${rule}")" != "inactive" && "${elapsed}" -lt 900 ]]; do
        [[ "${elapsed}" -eq 0 ]] && echo -n "   waiting for '${rule}' to go quiet first"
        echo -n "."
        sleep 30
        elapsed=$((elapsed + 30))
    done
    [[ "${elapsed}" -gt 0 ]] && echo " ${elapsed}s"
    if [[ "$(rule_state "${rule}")" != "inactive" ]]; then
        echo "   '${rule}' will not go quiet - the drill cannot tell its own firing" >&2
        echo "   from the one it inherited. Fix the standing condition first." >&2
        exit 2
    fi
done

# The metric must EXIST before the drill, or a "did not fire" verdict is about a
# missing meter rather than about the rule. This is the check phase 4e did not
# have, and its absence cost three drills.
if [[ -z "$(payorch_lag)" ]]; then
    echo "   payorch_consumer_lag is not being published by ${LEDGER}" >&2
    exit 2
fi
echo "   payorch_consumer_lag           $(payorch_lag)"
echo "   payorch_dlq_pending            $(dlq_pending)"
echo "   client-side lag series         $(client_lag_series)"
disarm
echo "   seams                          disarmed"
echo

# ==================================================================== ARM 1 ==

if [[ "${ARM}" == "all" || "${ARM}" == "lag" ]]; then
echo "=============================================================="
echo " ARM 1 - A SLOW CONSUMER"
echo "=============================================================="
echo "   ${LAG_N} payments against a consumer paused ${LAG_PAUSE_MS}ms per record."
echo "   Expect lag to climb well past the threshold of 50 and stay there"
echo "   long enough for a 2-minute 'all the time' window to hold."
echo

arm_pause "${LAG_PAUSE_MS}"
echo "   seam armed: PAUSE ${LAG_PAUSE_MS}ms"
echo -n "   publishing ${LAG_N} payments"
send "${LAG_N}"
echo " done"
sleep 20
peak="$(payorch_lag)"
echo "   lag right after publishing      ${peak}"
chk_gt "the backlog actually built" "${peak:-0}" 50

if wait_for_fire consumer-lag payorch_lag "${FIRE_BUDGET}"; then
    printf "   ok   %-46s %s\n" "consumer-lag fired on a slow consumer" "$(rule_state consumer-lag)"
else
    printf "   XX   %-46s %s\n" "consumer-lag fired on a slow consumer" "never"
    FAIL=$((FAIL+1))
fi

disarm
echo "   seam disarmed - the backlog should now drain"
if wait_for_resolve consumer-lag payorch_lag "${RESOLVE_BUDGET}"; then
    printf "   ok   %-46s %s\n" "and resolved once it drained" "inactive"
else
    printf "   XX   %-46s %s\n" "and resolved once it drained" "$(rule_state consumer-lag)"
    FAIL=$((FAIL+1))
fi
echo
fi

# ==================================================================== ARM 2 ==

if [[ "${ARM}" == "all" || "${ARM}" == "dead" ]]; then
echo "=============================================================="
echo " ARM 2 - A WEDGED CONSUMER"
echo "=============================================================="
echo "   The ledger is FROZEN with SIGSTOP while ${DEAD_N} payments are"
echo "   published. This is the worse incident and the one a lag threshold"
echo "   cannot see: the consumer computes its own lag, so a consumer that"
echo "   is not running reports none, and '> 50' on an absent series is"
echo "   never true. The rule has to fire on SILENCE."
echo
echo "   before: client-side lag series  $(client_lag_series)"
echo "           payorch_consumer_lag    $(payorch_lag)"

# PAUSE, NOT STOP, and the first run of this drill is why.
#
# `docker stop` was the obvious fault and it did not hold: the container was
# running again 39 seconds later, with RestartCount=0 - so something started it
# explicitly rather than a restart policy reviving it. The cause was never
# established. What it did to the drill is the point: the consumer came back
# inside the absence window, the series returned, and alertOnAbsent could never
# have fired. The arm would have reported "the alert did not fire on no data"
# about an alert that was never given any no-data to see.
#
# SIGSTOP is immune to all of that - no supervisor un-pauses a container - and
# it is the better fault anyway. A stopped consumer is a clean shutdown: the
# group is left, the partitions are revoked, the coordinator knows. A FROZEN one
# holds its partition assignment until session.timeout.ms (45s) expires, which
# is what a GC pause, a descheduled pod or a thread blocked on a merchant's
# webhook endpoint actually look like from the broker's side.
docker pause payorch-ledger-notifier >/dev/null 2>&1
echo "   ledger-notifier PAUSED (SIGSTOP - frozen, not shut down)"
echo -n "   publishing ${DEAD_N} payments into a topic nobody is reading"
send "${DEAD_N}"
echo " done"

echo "   after:  client-side lag series  $(client_lag_series)   <- the metric is gone"
echo "           payorch_consumer_lag    '$(payorch_lag)'   <- so is this one"
chk "lag series while the consumer is wedged" "$(client_lag_series)" "0"

# The whole point of the arm. The rule must fire on SILENCE.
if wait_for_fire consumer-lag client_lag_series "${FIRE_BUDGET}"; then
    printf "   ok   %-46s %s\n" "consumer-lag fired on NO DATA" "$(rule_state consumer-lag)"
else
    printf "   XX   %-46s %s\n" "consumer-lag fired on NO DATA" "never"
    FAIL=$((FAIL+1))
fi

docker unpause payorch-ledger-notifier >/dev/null 2>&1
echo "   ledger-notifier unpaused - waiting for it to rejoin its group"
for _ in $(seq 1 60); do
    [[ "$(curl -s -o /dev/null -w '%{http_code}' "${LEDGER}/actuator/health")" == "200" ]] && break
    sleep 3
done
echo "   back up, backlog now $(payorch_lag)"

if wait_for_resolve consumer-lag payorch_lag "${RESOLVE_BUDGET}"; then
    printf "   ok   %-46s %s\n" "and resolved once it caught up" "inactive"
else
    printf "   XX   %-46s %s\n" "and resolved once it caught up" "$(rule_state consumer-lag)"
    FAIL=$((FAIL+1))
fi
echo
fi

# ==================================================================== ARM 3 ==

if [[ "${ARM}" == "all" || "${ARM}" == "dlq" ]]; then
echo "=============================================================="
echo " ARM 3 - DEAD LETTERS NOBODY HAS HANDLED"
echo "=============================================================="
echo "   ${DLQ_N} records produced straight onto the DLQ topic."
echo
echo "   WHY NOT DRIVE THE LADDER. tools/loadtest/retry-dlq.sh already proves"
echo "   a failing message walks 5s, 1m and 10m into the DLQ, and it takes"
echo "   twelve minutes to do it. This drill is measuring the WATCHER, not"
echo "   the ladder, and a twelve-minute setup would only make the alert"
echo "   pipeline's own latency harder to see."
echo

before="$(dlq_pending)"
for i in $(seq 1 "${DLQ_N}"); do
    printf 'drill-%s:{"eventId":"00000000-0000-0000-0000-00000000000%s","note":"async-alert-drill synthetic dead letter"}\n' "$i" "$i"
done | MSYS_NO_PATHCONV=1 docker exec -i payorch-kafka-1 \
    /opt/kafka/bin/kafka-console-producer.sh \
        --bootstrap-server kafka-1:9092 \
        --topic payment.events.dlq \
        --property parse.key=true \
        --property key.separator=: >/dev/null 2>&1

echo "   produced ${DLQ_N} synthetic dead letters"
sleep 20
echo "   payorch_dlq_pending             $(dlq_pending)  (was ${before})"
chk_gt "the DLQ backlog is visible" "$(dlq_pending)" "${before:-0}"

if wait_for_fire dlq-pending dlq_pending "${FIRE_BUDGET}"; then
    printf "   ok   %-46s %s\n" "dlq-pending fired" "$(rule_state dlq-pending)"
else
    printf "   XX   %-46s %s\n" "dlq-pending fired" "never"
    FAIL=$((FAIL+1))
fi

# Triage rather than replay. These records are not payment events - replaying
# them would push nonsense onto payment.events and into the ladder. Moving the
# replay group forward is what an operator does when they have READ a dead
# letter and decided it is not replayable, and it is the same state transition
# the endpoint produces.
echo "   triaging: advancing the ledger-dlq-replay group past them"
kafka kafka-consumer-groups.sh --bootstrap-server kafka-1:9092 \
    --group ledger-dlq-replay --topic payment.events.dlq \
    --reset-offsets --to-latest --execute >/dev/null 2>&1
sleep 20
echo "   payorch_dlq_pending             $(dlq_pending)"
chk "the backlog is cleared" "$(dlq_pending)" "0"

if wait_for_resolve dlq-pending dlq_pending "${RESOLVE_BUDGET}"; then
    printf "   ok   %-46s %s\n" "and resolved after triage" "inactive"
else
    printf "   XX   %-46s %s\n" "and resolved after triage" "$(rule_state dlq-pending)"
    FAIL=$((FAIL+1))
fi
echo
fi

# ------------------------------------------------------ what was notified ---

echo "=============================================================="
echo " WHAT THE NOTIFICATION CHANNEL ACTUALLY RECEIVED"
echo "=============================================================="
echo "   A rule that evaluates correctly and notifies nobody is still a"
echo "   failure, and it is the half that is easy to forget to check."
echo
docker logs --since 60m payorch-alert-sink 2>&1 \
    | grep -E "consumer-lag|dlq-pending" \
    | python -c "
import json,sys
seen = 0
for line in sys.stdin:
    try: d = json.loads(line)
    except Exception: continue
    for name in d.get('names', []):
        if name in ('consumer-lag', 'dlq-pending'):
            print('     %-14s %-16s %s' % (d.get('status'), name, d.get('at','')[:19]))
            seen += 1
print('     (nothing)' if not seen else '')
" 2>/dev/null

echo "=============================================================="
if [[ "${FAIL}" -eq 0 ]]; then
    echo " PASS - both alerts fire and resolve, and consumer-lag fires on a"
    echo "        dead consumer as well as a slow one."
else
    echo " FAIL - ${FAIL} check(s) failed."
fi
echo "=============================================================="
exit "${FAIL}"
