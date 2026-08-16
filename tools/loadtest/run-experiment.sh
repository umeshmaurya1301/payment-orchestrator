#!/usr/bin/env bash
#
# Run one experiment end to end and leave a complete, reproducible artefact.
#
#   tools/loadtest/run-experiment.sh 00-control ramp.js
#   CHAOS_LATENCY_MS=3000 tools/loadtest/run-experiment.sh 01-latency ramp.js
#   TOXIC="latency mysql 500" tools/loadtest/run-experiment.sh 03-mysql ramp.js
#
# Everything an experiment needs to be trustworthy is in here rather than in a
# person's short-term memory:
#
#   * chaos is RESET before and after, both layers, every time. The single most
#     common way a chaos run is silently ruined is a toxic or an error rate left
#     over from the previous one, and it is invisible in the output.
#   * metrics capture starts before load and stops after it, so the CSV covers
#     the whole window including the recovery tail.
#   * the k6 JSON summary and the CSV land in one directory named for the run.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NAME="${1:?usage: run-experiment.sh <name> <script.js>}"
SCRIPT="${2:-ramp.js}"
OUT="${ROOT}/tools/loadtest/results/${NAME}"

MAX_RATE="${MAX_RATE:-500}"
STAGE_DURATION="${STAGE_DURATION:-45s}"
TOXIC="${TOXIC:-}"

mkdir -p "$OUT"

reset_all_chaos() {
    curl -s -X DELETE http://localhost:8085/_chaos >/dev/null 2>&1
    bash "${ROOT}/tools/chaos/toxic.sh" clear-all >/dev/null 2>&1
    for port in 8080 8081 8083; do
        curl -s -X DELETE "http://localhost:${port}/actuator/chaosbeans" >/dev/null 2>&1
        curl -s -X DELETE "http://localhost:${port}/actuator/chaosseams" >/dev/null 2>&1
    done
}

echo "=== ${NAME}: resetting all chaos layers ==="
reset_all_chaos

if [[ -n "$TOXIC" ]]; then
    echo "=== applying toxic: ${TOXIC} ==="
    # Word splitting is intended here: TOXIC is "latency mysql 500".
    # shellcheck disable=SC2086
    bash "${ROOT}/tools/chaos/toxic.sh" $TOXIC >/dev/null
fi

echo "=== starting metrics capture ==="
bash "${ROOT}/tools/loadtest/capture-metrics.sh" "${OUT}/metrics.csv" 2 &
CAPTURE_PID=$!
# A moment of capture before load starts, so the CSV has an idle reference row.
sleep 4

echo "=== running ${SCRIPT} (max ${MAX_RATE} rps, ${STAGE_DURATION} stages) ==="
MSYS_NO_PATHCONV=1 docker run --rm \
    --network payorch_payorch \
    -v "$(cd "$ROOT" && pwd -W)/tools/loadtest:/scripts" \
    -v "$(cd "$OUT" && pwd -W):/out" \
    -e EDGE=http://payments-edge:8080 \
    -e SIMULATOR=http://mock-psp-simulator:8085 \
    -e MAX_RATE="$MAX_RATE" \
    -e STAGE_DURATION="$STAGE_DURATION" \
    -e CHAOS_LATENCY_MS="${CHAOS_LATENCY_MS:-0}" \
    -e CHAOS_ERROR_RATE="${CHAOS_ERROR_RATE:-0}" \
    -e CHAOS_HANG_RATE="${CHAOS_HANG_RATE:-0}" \
    -e CHAOS_DUPLICATE_RATE="${CHAOS_DUPLICATE_RATE:-0}" \
    -e RATE="${RATE:-50}" \
    -e DURATION="${DURATION:-30m}" \
    -e NOISY_RATE="${NOISY_RATE:-400}" \
    -e POLITE_RATE="${POLITE_RATE:-10}" \
    grafana/k6:latest run --summary-export=/out/summary.json "/scripts/${SCRIPT}" \
    2>&1 | tee "${OUT}/k6.log"

# Keep capturing through the recovery tail. How long a system takes to come back
# is a measurement in its own right, and stopping the capture with the load
# throws it away.
echo "=== capturing recovery tail ==="
sleep 20
kill "$CAPTURE_PID" 2>/dev/null
wait "$CAPTURE_PID" 2>/dev/null

echo "=== resetting all chaos layers ==="
reset_all_chaos

echo
echo "=== ${NAME} summary ==="
python "${ROOT}/tools/loadtest/summarise-metrics.py" "${OUT}/metrics.csv"
echo
echo "artefacts in ${OUT}"
