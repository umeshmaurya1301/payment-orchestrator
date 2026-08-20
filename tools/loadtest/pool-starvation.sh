#!/usr/bin/env bash
#
# Phase 7, section 10: unbounded concurrency does not remove a bottleneck, it
# relocates it.
#
#   tools/loadtest/pool-starvation.sh            # both arms, ~5 minutes
#   LATENCY_MS=800 RPS=40 tools/loadtest/pool-starvation.sh
#
# THE CLAIM THIS EXISTS TO TEST
#
# This project has run virtual threads since phase 0, deliberately, and the
# phase-0 note says why: "virtual threads do not fix a bounded resource, they
# move where the queue forms." That is an assertion until somebody watches the
# queue move.
#
# So: put 800ms of latency in front of MySQL and send steady load. Virtual
# threads mean the platform threads never block and the JVM will happily create
# thousands of them. The Hikari pool is still 20 connections. The queue does not
# disappear - it relocates from the thread pool to the connection pool, and the
# numbers that show it are:
#
#   hikaricp_connections_active    FLAT at maximum-pool-size    the ceiling
#   hikaricp_connections_pending   CLIMBING                     the new queue
#   jvm_threads_live_threads       roughly flat                 they are virtual
#   throughput                     FLAT                         the point
#   latency                        CLIMBING                     the cost
#
# WHAT WOULD FALSIFY IT
#
# If pending stayed at zero while throughput held, the pool would not be the
# constraint and the demo would be describing something else. The assertions at
# the end are written so that outcome fails rather than passes quietly.
#
# THE TRAP THIS DEMO SETS FOR ITS OWN READER
#
# The phase's own trap list names it: do not conclude "virtual threads are slow"
# from this graph. Nothing here is a measurement of virtual threads. It is a
# measurement of a 20-connection pool behind an 800ms database, and platform
# threads would produce the same ceiling sooner and with more memory.

set -uo pipefail

EDGE="${EDGE:-http://localhost:8080}"
ORCH="${ORCH:-http://localhost:8081}"
API_KEY="${API_KEY:-pk_test_dev_merchant_key}"

LATENCY_MS="${LATENCY_MS:-800}"
DURATION="${DURATION:-60}"
CONCURRENCY="${CONCURRENCY:-60}"
SAMPLE="${SAMPLE:-3}"

OUT="${OUT:-tools/loadtest/results/07-pool-starvation}"
FAIL=0

mkdir -p "${OUT}"

# Always, even on a failed run. A toxic left behind is the fastest way to
# produce a graph that means nothing, and it is invisible.
cleanup() {
    bash tools/chaos/toxic.sh clear-all >/dev/null 2>&1 || true
    [[ -n "${LOAD_PID:-}" ]] && kill "${LOAD_PID}" 2>/dev/null
}
trap cleanup EXIT

metric() {
    curl -s --max-time 5 "${ORCH}/actuator/prometheus" 2>/dev/null \
        | grep -E "^$1" | awk '{print $NF}' | head -1
}

# Hikari publishes state as a labelled gauge rather than one series per state.
hikari() {
    curl -s --max-time 5 "${ORCH}/actuator/prometheus" 2>/dev/null \
        | grep -E "^hikaricp_connections_${1}\{" | awk '{print $NF}' | head -1
}

send_load() {
    local seconds="$1" tag="$2" deadline
    deadline=$(( $(date +%s) + seconds ))
    while [[ "$(date +%s)" -lt "${deadline}" ]]; do
        for _ in $(seq 1 "${CONCURRENCY}"); do
            curl -s -o /dev/null --max-time 60 \
                -X POST "${EDGE}/v1/payments" \
                -H "Content-Type: application/json" \
                -H "X-Api-Key: ${API_KEY}" \
                -H "Idempotency-Key: ${tag}-$(date +%s%N)-$RANDOM" \
                -d '{"amountMinor":4200,"currency":"INR","card":{"number":"4242424242424242","expiryMonth":12,"expiryYear":2030,"cvv":"123"},"merchantReference":"'"${tag}"'"}' &
        done
        wait
    done
}

# One row per sample. Written to a CSV as well as the screen, so the graph is an
# artefact that survives in a diff rather than a screenshot.
watch_pool() {
    local seconds="$1" label="$2" csv="$3"
    local elapsed=0
    echo "t,active,idle,pending,max,threads,payments" > "${csv}"
    printf "   %-6s %8s %8s %8s %8s %9s %10s\n" "t" "active" "idle" "pending" "max" "threads" "payments"
    printf "   %-6s %8s %8s %8s %8s %9s %10s\n" "------" "--------" "--------" "--------" "--------" "---------" "----------"
    while [[ "${elapsed}" -lt "${seconds}" ]]; do
        local a i p m th n
        a="$(hikari active)"; i="$(hikari idle)"; p="$(hikari pending)"; m="$(hikari max)"
        th="$(metric jvm_threads_live_threads)"
        n="$(docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B \
              -e "SELECT COUNT(*) FROM payment WHERE merchant_reference='${label}';" 2>/dev/null | tr -d '\r')"
        printf "   %-6s %8s %8s %8s %8s %9s %10s\n" "${elapsed}s" "${a:-?}" "${i:-?}" "${p:-?}" "${m:-?}" "${th%%.*}" "${n:-?}"
        echo "${elapsed},${a},${i},${p},${m},${th},${n}" >> "${csv}"
        sleep "${SAMPLE}"
        elapsed=$(( elapsed + SAMPLE ))
    done
}

run_arm() {
    local label="$1" tag="$2" latency="$3"

    echo
    echo "=============================================================="
    echo " ${label}"
    echo "=============================================================="

    bash tools/chaos/toxic.sh clear-all >/dev/null 2>&1
    if [[ "${latency}" -gt 0 ]]; then
        bash tools/chaos/toxic.sh latency mysql "${latency}" >/dev/null 2>&1
        echo "   toxiproxy: +${latency}ms on every MySQL round trip"
    else
        echo "   toxiproxy: clear"
    fi

    send_load "${DURATION}" "${tag}" &
    LOAD_PID=$!
    sleep 2
    watch_pool "${DURATION}" "${tag}" "${OUT}/${tag}.csv"
    wait "${LOAD_PID}" 2>/dev/null
    LOAD_PID=""
}

echo "=============================================================="
echo " PREFLIGHT"
echo "=============================================================="
printf "   %-26s %s\n" "hikari maximum-pool-size" "$(hikari max)"
printf "   %-26s %s\n" "virtual threads" "$(curl -s "${ORCH}/actuator/prometheus" | grep -c 'jvm_threads_live' )"
printf "   %-26s %s\n" "concurrency per wave" "${CONCURRENCY}"
printf "   %-26s %s\n" "arm duration" "${DURATION}s"
if [[ -z "$(hikari max)" ]]; then
    echo "   cannot read the orchestrator's Hikari gauges" >&2
    exit 2
fi

run_arm "ARM A - HEALTHY DATABASE" "starv-fast" 0
FAST_N="$(docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B \
    -e "SELECT COUNT(*) FROM payment WHERE merchant_reference='starv-fast';" 2>/dev/null | tr -d '\r')"
FAST_PENDING_MAX="$(awk -F, 'NR>1 && $4+0>m {m=$4+0} END {print m+0}' "${OUT}/starv-fast.csv")"

run_arm "ARM B - 800ms IN FRONT OF MYSQL" "starv-slow" "${LATENCY_MS}"
SLOW_N="$(docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B \
    -e "SELECT COUNT(*) FROM payment WHERE merchant_reference='starv-slow';" 2>/dev/null | tr -d '\r')"
SLOW_PENDING_MAX="$(awk -F, 'NR>1 && $4+0>m {m=$4+0} END {print m+0}' "${OUT}/starv-slow.csv")"
SLOW_ACTIVE_MAX="$(awk -F, 'NR>1 && $2+0>m {m=$2+0} END {print m+0}' "${OUT}/starv-slow.csv")"
POOL_MAX="$(hikari max)"

echo
echo "=============================================================="
echo " WHERE THE QUEUE WENT"
echo "=============================================================="
printf "   %-34s %s\n" "payments completed, healthy" "${FAST_N}"
printf "   %-34s %s\n" "payments completed, +${LATENCY_MS}ms" "${SLOW_N}"
printf "   %-34s %s\n" "peak hikari pending, healthy" "${FAST_PENDING_MAX}"
printf "   %-34s %s\n" "peak hikari pending, +${LATENCY_MS}ms" "${SLOW_PENDING_MAX}"
printf "   %-34s %s / %s\n" "peak active vs pool size" "${SLOW_ACTIVE_MAX}" "${POOL_MAX%%.*}"
echo

if [[ "${SLOW_PENDING_MAX}" -gt "${FAST_PENDING_MAX}" ]]; then
    printf "   ok   %-46s %s\n" "the queue moved to the connection pool" "${SLOW_PENDING_MAX}"
else
    printf "   XX   %-46s %s\n" "pending never climbed - the pool is not the constraint" "${SLOW_PENDING_MAX}"
    echo "        Raise CONCURRENCY or LATENCY_MS. As it stands this graph is"
    echo "        describing something other than pool starvation."
    FAIL=$((FAIL+1))
fi

if [[ "${SLOW_N:-0}" -lt "${FAST_N:-0}" ]]; then
    printf "   ok   %-46s %s < %s\n" "throughput fell while threads were free" "${SLOW_N}" "${FAST_N}"
else
    printf "   XX   %-46s %s >= %s\n" "throughput did not fall" "${SLOW_N}" "${FAST_N}"
    FAIL=$((FAIL+1))
fi

echo
echo "   CSVs: ${OUT}/starv-fast.csv, ${OUT}/starv-slow.csv"
echo "=============================================================="
if [[ "${FAIL}" -eq 0 ]]; then
    echo " PASS - the bottleneck relocated. It did not disappear."
else
    echo " FAIL - ${FAIL} check(s) failed."
fi
echo "=============================================================="
exit "${FAIL}"
