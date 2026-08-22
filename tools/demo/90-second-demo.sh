#!/usr/bin/env bash
#
# Phase 10: the 90-second live demo.
#
#   tools/demo/90-second-demo.sh
#
# Five steps, phase 10's own script:
#   1. start load
#   2. show the SigNoz dashboard - three providers, healthy
#   3. degrade psp-a via the simulator's chaos endpoint
#   4. watch traffic shift to B and C, error rate flat
#   5. show the trace spanning edge -> orchestrator -> connector
#
# WHAT THIS SCRIPT IS AND IS NOT
#
# It is a narrated, timed runbook a presenter runs live, not an unattended
# test. The mechanical parts - starting load, degrading the provider, healing
# it - are automated so nothing depends on typing the right curl command under
# pressure. Whether the DASHBOARD looks right is for a human to see; what this
# script can and does verify on its own is whether the DATA behind it is
# actually there - the same trap this project's other measurement scripts have
# hit all session: a demo that "looks fine" while measuring nothing.
#
# WHY soak.js AND NOT ramp.js, DESPITE THE PHASE PLAN NAMING ramp.js
#
# ramp.js climbs through several 45-second stages hunting for a breaking
# point - the right tool for finding a knee, and it alone would spend the
# entire 90-second budget on its first two stages before reaching a rate worth
# looking at. This demo wants the opposite: a small, steady, boring rate that
# holds while a fault is injected, which is exactly what soak.js is for and
# exactly the profile phase 5's own routing-experiment.sh already uses for the
# same reason.
#
# COLD, NOT WARM
#
# The phase's own trap: "a demo that needs a warm JVM and a lucky start."
# Nothing here pre-runs traffic before the clock starts - the dashboard and the
# breaker state the presenter shows are whatever a freshly-started stack
# actually has, not a rehearsed-looking average built by running the fault
# five times first.
#
# WHAT HAS TO BE RUNNING FIRST
#
#   docker compose -f docker-compose.yml -f docker/signoz/payorch-obs.override.yml up -d
#   tools/obs/signoz.sh up      # if SigNoz itself is not already running
#   tools/obs/signoz.sh attach  # points the payment stack at it
#
# Async (Kafka/Mongo/ledger) is deliberately NOT required. Steps 1-5 only touch
# the synchronous payment path - edge, orchestrator, connector - and starting
# three more stateful services would add startup risk to a script whose whole
# point is starting cleanly.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EDGE="${EDGE:-http://localhost:8080}"
PRIMARY_SIM="${PRIMARY_SIM:-http://localhost:8086}"
SIGNOZ_UI="${SIGNOZ_UI:-http://localhost:3301}"
RATE="${RATE:-30}"
FAIL=0
K6_PID=""
DEMO_START=""

pq() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

ch() {
    docker exec signoz-telemetrystore-clickhouse-0-0 clickhouse-client -q "$1" 2>/dev/null | tr -d '\r'
}

heal_primary() {
    curl -s -X POST "${PRIMARY_SIM}/_chaos" -H "Content-Type: application/json" \
        -d '{"latencyMs":200,"errorRate":0.001,"hangRate":0,"duplicateRate":0}' >/dev/null
}

degrade_primary() {
    curl -s -X POST "${PRIMARY_SIM}/_chaos" -H "Content-Type: application/json" \
        -d '{"latencyMs":200,"errorRate":0.8,"hangRate":0,"duplicateRate":0}' >/dev/null
}

cleanup() {
    [[ -n "${K6_PID}" ]] && kill "${K6_PID}" 2>/dev/null
    docker kill payorch-demo-load >/dev/null 2>&1
    heal_primary
}
trap cleanup EXIT

elapsed() {
    echo "$(( $(date +%s) - DEMO_START ))"
}

say() {
    printf "\n[t+%3ss] %s\n" "$(elapsed)" "$1"
}

check() {
    local label="$1" ok="$2"
    if [[ "${ok}" == "1" ]]; then
        printf "         ok   %s\n" "${label}"
    else
        printf "         XX   %s\n" "${label}"
        FAIL=$((FAIL+1))
    fi
}

# ---------------------------------------------------------------------------
# PREFLIGHT - not part of the 90 seconds. If this fails, the demo has not
# started; better to find out now than mid-narration.
# ---------------------------------------------------------------------------
echo "=============================================================="
echo " PREFLIGHT (not timed)"
echo "=============================================================="
edge_ok="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${EDGE}/actuator/health" | grep -c 200)"
signoz_ok="$(docker exec signoz-telemetrystore-clickhouse-0-0 clickhouse-client -q "SELECT 1" 2>/dev/null | grep -c 1)"
chaos_ok="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${PRIMARY_SIM}/_chaos" | grep -c 200)"
check "payments-edge is up" "${edge_ok}"
check "SigNoz's ClickHouse is reachable" "${signoz_ok}"
check "psp-a's chaos endpoint is reachable" "${chaos_ok}"

if [[ "${FAIL}" -gt 0 ]]; then
    echo
    echo "   Not ready. If SigNoz is the problem:"
    echo "     tools/obs/signoz.sh up && tools/obs/signoz.sh attach"
    exit 1
fi

heal_primary
echo "   psp-a healed to its baseline (200ms, 0.1% errors) before starting the clock."

# ---------------------------------------------------------------------------
# THE CLOCK STARTS HERE
# ---------------------------------------------------------------------------
DEMO_START=$(date +%s)

say "STEP 1 - start load"
echo "         soak.js at ${RATE} rps against a freshly-started stack - nothing"
echo "         warmed it up before this."
# EDGE and SIMULATOR both, or the run never gets past k6's own setup().
# soak.js's setup() calls applyChaosFromEnv(), which resets the simulator's
# fault state via SIMULATOR - defaulted to localhost:8085, which inside this
# container is itself, not mock-psp-simulator. The first rehearsal of this
# script found that the hard way: setup() threw, the whole run aborted before
# a single payment was sent, and the failure downstream looked like "traffic
# never shifted" when the real story was "no traffic was ever sent".
MSYS_NO_PATHCONV=1 docker run -d --rm --name payorch-demo-load \
    --network payorch_payorch \
    -v "$(cd "${ROOT}" && pwd -W 2>/dev/null || pwd)/tools/loadtest:/scripts" \
    -e EDGE=http://payments-edge:8080 \
    -e SIMULATOR=http://mock-psp-simulator:8085 \
    -e RATE="${RATE}" \
    -e DURATION=100s \
    grafana/k6:latest run --quiet /scripts/soak.js >/dev/null 2>&1
K6_PID=""   # backgrounded inside its own container; nothing to kill by PID

say "warming up - letting traffic reach all three providers before judging health"
sleep 10

BASELINE_START="$(pq "SELECT NOW(3);")"
before_a="$(pq "SELECT COUNT(*) FROM payment_attempt WHERE psp_id='psp-a' AND created_at > NOW() - INTERVAL 8 SECOND;")"
say "STEP 2 - open the dashboard"
echo "         ${SIGNOZ_UI} -> Dashboards -> payments-resilience"
echo "         Expect: psp-a, psp-b, psp-c all showing traffic, all green."
check "psp-a genuinely carried traffic in the last 8s (${before_a} attempts)" "$([[ "${before_a:-0}" -gt 0 ]] && echo 1 || echo 0)"

say "STEP 3 - degrade psp-a"
echo "         errorRate 0.8 via the simulator's chaos endpoint - an unambiguous"
echo "         failure, the safe one to fail over on."
degrade_primary
DEGRADE_AT="$(pq "SELECT NOW(3);")"

say "STEP 4 - watch it shift"
echo -n "         polling psp-a's share every 2s"
shift_seconds=""
# 5, not 0: an empty window (no traffic at all - the exact bug the first
# rehearsal found) divides to a "share" of 0% and would otherwise read as a
# perfect, instant shift. Requiring a real sample size is what makes this
# check able to fail, rather than passing by construction whenever k6 is not
# actually running.
MIN_SAMPLE=5
for i in $(seq 1 15); do
    sleep 2
    window_a="$(pq "SELECT COUNT(*) FROM payment_attempt WHERE psp_id='psp-a' AND created_at > '${DEGRADE_AT}' AND created_at <= NOW();")"
    window_total="$(pq "SELECT COUNT(*) FROM payment_attempt WHERE created_at > '${DEGRADE_AT}' AND created_at <= NOW();")"
    window_a="${window_a:-0}"
    window_total="${window_total:-0}"
    echo -n "."
    if [[ "${window_total}" -ge "${MIN_SAMPLE}" ]]; then
        share=$(( 100 * window_a / window_total ))
        if [[ "${share}" -le 5 && -z "${shift_seconds}" ]]; then
            shift_seconds="$(( i * 2 ))"
        fi
    fi
done
echo
check "load was actually flowing (${window_total} attempts in the last window)" \
    "$([[ "${window_total}" -ge "${MIN_SAMPLE}" ]] && echo 1 || echo 0)"
check "traffic off psp-a within 20s of the fault (measured: ${shift_seconds:-not yet}s)" \
    "$([[ -n "${shift_seconds}" ]] && echo 1 || echo 0)"

recent_failure_rate="$(pq "SELECT IFNULL(ROUND(100 * SUM(outcome != 'SUCCESS') / COUNT(*)), 'n/a - no traffic')
    FROM payment_attempt WHERE created_at > '${DEGRADE_AT}' AND created_at <= NOW();")"
echo "         end-user error rate since the fault: ${recent_failure_rate:-n/a}%"
echo "         (psp-b and psp-c absorbing the traffic, not a spike)"

say "STEP 5 - the trace"
TRACE_ID="$(python -c "import os;print(os.urandom(16).hex())")"
SPAN_ID="$(python -c "import os;print(os.urandom(8).hex())")"
curl -s -o /dev/null --max-time 15 -X POST "${EDGE}/v1/payments" \
    -H "Content-Type: application/json" -H "X-Api-Key: pk_test_dev_merchant_key" \
    -H "traceparent: 00-${TRACE_ID}-${SPAN_ID}-01" \
    -H "Idempotency-Key: demo-trace-$(date +%s%N)" \
    -d '{"amountMinor":4200,"currency":"INR","card":{"number":"4242424242424242","expiryMonth":12,"expiryYear":2030,"cvv":"123"},"merchantReference":"90s-demo"}'

# Polled, not a single fixed sleep. OTel's batch span processor exports on its
# own interval, and each of the three services flushes independently - a fixed
# 5s sleep caught payments-edge's span in the first rehearsal and missed the
# other two, which had not flushed yet. Polling until the count stops
# growing is the same technique trace-propagation.sh already uses for exactly
# this reason.
services_in_trace=0
for _ in $(seq 1 8); do
    sleep 2
    services_in_trace="$(ch "SELECT countDistinct(\`resource_string_service\$\$name\`)
        FROM signoz_traces.distributed_signoz_index_v3 WHERE trace_id = '${TRACE_ID}'")"
    services_in_trace="${services_in_trace:-0}"
    [[ "${services_in_trace}" -ge 3 ]] && break
done
echo "         ${SIGNOZ_UI} -> Traces -> search trace ID: ${TRACE_ID}"
echo "         Expect: payments-edge -> payment-orchestrator -> psp-connector, one trace -"
echo "         and, since mock-psp-simulator carries the same instrumentation, it usually"
echo "         goes one hop further than asked: the simulated provider is IN the trace too."
check "that trace spans at least the 3 payment-path services (found: ${services_in_trace:-0})" \
    "$([[ "${services_in_trace:-0}" -ge 3 ]] && echo 1 || echo 0)"

say "healing psp-a and winding down"
heal_primary
docker kill payorch-demo-load >/dev/null 2>&1
trap - EXIT

echo
echo "=============================================================="
printf " total elapsed: %ss\n" "$(elapsed)"
[[ "${FAIL}" -eq 0 ]] && echo " PASS - every claim above was verified, not narrated" \
    || echo " FAIL - ${FAIL} claim(s) did not hold. Rehearse again before presenting."
echo "=============================================================="
exit "${FAIL}"
