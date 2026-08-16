#!/usr/bin/env bash
#
# Phase 5's instrument: degrade the provider carrying the traffic, mid-run, and
# record where the traffic goes and what the caller sees.
#
#   tools/loadtest/routing-experiment.sh 5a-static-priority
#
#   DEGRADE_AFTER=60  DEGRADE_FOR=120  MAX_RATE=60 \
#   DEGRADE_MODE=errors  tools/loadtest/routing-experiment.sh 5a-static-priority
#
# WHY A SEPARATE RUNNER FROM run-experiment.sh
#
# run-experiment.sh applies one fault for a whole run, which is right for
# measuring a component's steady-state cost. Phase 5 is about a TRANSITION: the
# interesting number is how long the system takes to notice and move, so the
# fault has to arrive partway through a run that is already at steady state, and
# the clock has to start at that instant.
#
# It also captures two things run-experiment.sh does not:
#
#   * per-provider attempt counts over time, which is the traffic-shift graph;
#   * end-user outcomes over the same clock, because traffic moving while users
#     see errors is not a success. The phase plan lists measuring the shift and
#     not the error rate as its own trap.
#
# ROUTING IS STEERED BY PRIORITY, AT RUNTIME
#
# mockpsp keeps its committed priority of 10 and its healthy simulator so
# experiments 00-06 reproduce exactly as written. This script demotes it for the
# duration and restores it on the way out, including on interrupt - the same
# technique 3f introduced and experiment 06 measured.
#
# DEGRADE_MODE
#
#   errors   errorRate 0.8. An unambiguous failure: the provider answered, and
#            it said no. Failover is SAFE here - see phase 5's nuance section.
#   hang     hangRate 0.8. Ambiguous: the request was sent and nothing came
#            back, so the card may or may not have been charged. Failover is
#            NOT safe, and the payment must land in UNKNOWN. The phase plan
#            calls testing failover with only clean failures a trap; this is
#            the arm that catches it.
#   slow     latencyMs 4000. Succeeding but breaching its latency contract -
#            a different problem from failing, and the two should not produce
#            the same health score.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NAME="${1:?usage: routing-experiment.sh <name>}"
OUT="${ROOT}/tools/loadtest/results/${NAME}"

MAX_RATE="${MAX_RATE:-60}"
DEGRADE_AFTER="${DEGRADE_AFTER:-60}"
DEGRADE_FOR="${DEGRADE_FOR:-120}"
RECOVER_FOR="${RECOVER_FOR:-60}"
DEGRADE_MODE="${DEGRADE_MODE:-errors}"

# The provider under test and the simulator that backs it.
PRIMARY="${PRIMARY:-psp-a}"
PRIMARY_SIM="${PRIMARY_SIM:-http://localhost:8086}"

mkdir -p "$OUT"

mysql_do() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -e "$1" 2>/dev/null
}

# psp-a first, then psp-c, then psp-b, with mockpsp demoted out of the way.
# psp-b last on purpose: it is the 2.5s/50 TPS provider, so a system that fails
# over to it should look visibly worse than one that fails over to psp-c, and a
# health score that cannot tell them apart is not doing anything useful.
set_phase5_priorities() {
    mysql_do "UPDATE psp_config SET priority = CASE psp_id
                  WHEN 'psp-a' THEN 10 WHEN 'psp-c' THEN 20
                  WHEN 'psp-b' THEN 30 WHEN 'mockpsp' THEN 100 END;"
}

restore_priorities() {
    mysql_do "UPDATE psp_config SET priority = CASE psp_id
                  WHEN 'mockpsp' THEN 10 WHEN 'psp-a' THEN 20
                  WHEN 'psp-b' THEN 30 WHEN 'psp-c' THEN 40 END;"
}

# The primary's committed personality, restored on the way out. psp-a is the
# "fast, reliable" provider: 200ms, 0.1% errors.
heal_primary() {
    curl -s -X POST "${PRIMARY_SIM}/_chaos" -H "Content-Type: application/json" \
        -d '{"latencyMs":200,"errorRate":0.001,"hangRate":0,"duplicateRate":0}' >/dev/null
}

degrade_primary() {
    case "$DEGRADE_MODE" in
        errors) body='{"latencyMs":200,"errorRate":0.8,"hangRate":0,"duplicateRate":0}' ;;
        hang)   body='{"latencyMs":200,"errorRate":0,"hangRate":0.8,"duplicateRate":0}' ;;
        slow)   body='{"latencyMs":4000,"errorRate":0,"hangRate":0,"duplicateRate":0}' ;;
        *) echo "unknown DEGRADE_MODE: $DEGRADE_MODE" >&2; exit 2 ;;
    esac
    curl -s -X POST "${PRIMARY_SIM}/_chaos" -H "Content-Type: application/json" -d "$body" >/dev/null
}

CAPTURE_PID=""
K6_PID=""
cleanup() {
    [[ -n "$CAPTURE_PID" ]] && kill "$CAPTURE_PID" 2>/dev/null
    docker kill "payorch-routing-${NAME}" >/dev/null 2>&1
    heal_primary
    restore_priorities >/dev/null 2>&1
    bash "${ROOT}/tools/chaos/toxic.sh" clear-all >/dev/null 2>&1
}
trap cleanup EXIT

echo "=== ${NAME}: ${DEGRADE_MODE} on ${PRIMARY} after ${DEGRADE_AFTER}s, for ${DEGRADE_FOR}s ==="
heal_primary
set_phase5_priorities
echo "routing: psp-a(10) -> psp-c(20) -> psp-b(30), mockpsp demoted to 100"
echo "(psp-connector polls psp_config every 2s; the orchestrator reads it per request)"

bash "${ROOT}/tools/loadtest/capture-routing.sh" "${OUT}/routing.csv" 2 &
CAPTURE_PID=$!
bash "${ROOT}/tools/loadtest/capture-metrics.sh" "${OUT}/metrics.csv" 2 &
METRICS_PID=$!
sleep 4

TOTAL=$((DEGRADE_AFTER + DEGRADE_FOR + RECOVER_FOR))
echo "=== load: ${MAX_RATE} rps for ${TOTAL}s ==="
MSYS_NO_PATHCONV=1 docker run --rm --name "payorch-routing-${NAME}" \
    --network payorch_payorch \
    -v "$(cd "$ROOT" && pwd -W)/tools/loadtest:/scripts" \
    -v "$(cd "$OUT" && pwd -W):/out" \
    -e EDGE=http://payments-edge:8080 \
    -e SIMULATOR=http://mock-psp-simulator:8085 \
    -e RATE="${MAX_RATE}" \
    -e DURATION="${TOTAL}s" \
    grafana/k6:latest run --summary-export=/out/summary.json /scripts/soak.js \
    > "${OUT}/k6.log" 2>&1 &
K6_PID=$!

sleep "$DEGRADE_AFTER"
DEGRADE_AT=$(date +%s)
echo "=== [t+${DEGRADE_AFTER}s] degrading ${PRIMARY}: ${DEGRADE_MODE} ==="
degrade_primary
echo "$DEGRADE_AT" > "${OUT}/degraded_at"

sleep "$DEGRADE_FOR"
RECOVER_AT=$(date +%s)
echo "=== [t+$((DEGRADE_AFTER + DEGRADE_FOR))s] healing ${PRIMARY} ==="
heal_primary
echo "$RECOVER_AT" > "${OUT}/recovered_at"

wait "$K6_PID" 2>/dev/null
sleep 6
kill "$CAPTURE_PID" "$METRICS_PID" 2>/dev/null
wait "$CAPTURE_PID" "$METRICS_PID" 2>/dev/null

echo
echo "=== ${NAME}: where the traffic went ==="
python "${ROOT}/tools/loadtest/summarise-routing.py" "$OUT"
echo
echo "artefacts in ${OUT}"
