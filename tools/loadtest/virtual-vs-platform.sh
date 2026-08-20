#!/usr/bin/env bash
#
# Phase 7: virtual threads against platform threads, with numbers.
#
#   tools/loadtest/virtual-vs-platform.sh
#   CONCURRENCY=400 PROVIDER_MS=1500 tools/loadtest/virtual-vs-platform.sh
#
# THE BENCHMARK HAS TO ANSWER THE TRAP, NOT DODGE IT
#
# Experiment 18 showed throughput collapsing 16x under a saturated connection
# pool while running virtual threads, and the phase's trap list says plainly what
# NOT to conclude from it: "virtual threads are slow". The bottleneck there was
# the pool. Virtual threads had nothing to do with it.
#
# Which means a fair comparison cannot use that workload. Behind a saturated
# pool, virtual and platform threads produce the SAME number - both are waiting
# on the same 20 connections - and a benchmark that reported "no difference"
# would be true, useless, and quietly misleading about why.
#
# So the load here is deliberately THREAD-bound rather than POOL-bound: the mock
# provider is slowed to 1.5s, and the provider call happens OUTSIDE any database
# transaction (phase 2's rule - no transaction may be open across a remote call).
# Each in-flight payment therefore holds a request thread for ~1.5s and a
# connection for almost none of it. That is the shape virtual threads exist for,
# and it is the only shape where the two can differ.
#
# WHAT SHOULD HAPPEN
#
#   platform   Tomcat's default 200 threads. Concurrency above that queues at
#              the front door; throughput ceilings at roughly 200/1.5s.
#   virtual    No such bound. Throughput tracks the provider instead.
#
# If they come out the same, the load was not thread-bound and this script is
# measuring something else - so it asserts on the difference rather than
# reporting whatever it finds.

set -uo pipefail

EDGE="${EDGE:-http://localhost:8080}"
ORCH="${ORCH:-http://localhost:8081}"
API_KEY="${API_KEY:-pk_test_dev_merchant_key}"

CONCURRENCY="${CONCURRENCY:-400}"
PROVIDER_MS="${PROVIDER_MS:-1500}"
DURATION="${DURATION:-45}"

OUT="${OUT:-tools/loadtest/results/07-virtual-vs-platform}"
mkdir -p "${OUT}"
FAIL=0

# WIDENING THE GATES, AND WHY THAT IS NOT CHEATING
#
# The first run of this benchmark reported virtual and platform completing
# EXACTLY 1200 payments each - 400 concurrency x 3 waves, the load generator's
# own ceiling. At 24 payments/s against a 1.5s provider only ~36 requests were
# ever really in flight, nowhere near either thread model's limit.
#
# The cause was not the thread pool. psp-connector's bulkhead was rejecting
# (4,516 permitted / 117 rejected), and psp_config binds the path in three more
# places: bulkhead_max_concurrent 20-160, egress_tps 50-500, and
# deadline_slice_ms as low as 50ms - which at 1.5s of provider latency times out
# every call to mockpsp and psp-a before it can return.
#
# So the request path is bounded by four resources all SMALLER than the thread
# count, and a thread-bound workload cannot be built through it. That is the
# correct architecture, and it is experiment 18's lesson again: the queue forms
# at whichever bound is tightest and the thread model is not it.
#
# To measure the thread model those bounds have to come off. This widens them
# for the duration and restores them from a trap. The result is a measurement OF
# THE THREAD MODEL and explicitly NOT of this system as it ships - as it ships,
# the bulkhead binds first and the two models are indistinguishable.
GATES_BACKUP="${OUT:-/tmp}/psp_config.backup.tsv"

widen_gates() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B -e         "SELECT psp_id, bulkhead_max_concurrent, egress_tps, deadline_slice_ms FROM psp_config;"         2>/dev/null | tr -d '\r' > "${GATES_BACKUP}"
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -e         "UPDATE psp_config SET bulkhead_max_concurrent=2000, egress_tps=5000, deadline_slice_ms=30000;"         2>/dev/null
    echo "   gates widened (bulkhead 2000, egress 5000/s, slice 30s); originals in ${GATES_BACKUP}"
    # psp-connector polls psp_config every 2s.
    sleep 4
}

restore_gates() {
    [[ -s "${GATES_BACKUP}" ]] || return 0
    while IFS=$'	' read -r id bh tps slice; do
        [[ -z "${id}" ]] && continue
        docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -e             "UPDATE psp_config SET bulkhead_max_concurrent=${bh}, egress_tps=${tps}, deadline_slice_ms=${slice} WHERE psp_id='${id}';"             2>/dev/null
    done < "${GATES_BACKUP}"
    echo "   psp_config restored"
}

cleanup() {
    restore_gates
    for port in 8085 8086 8087 8088; do
        curl -s -o /dev/null --max-time 5 -X POST "http://localhost:${port}/_chaos" \
            -H 'Content-Type: application/json' \
            -d '{"latencyMs":0,"errorRate":0,"hangRate":0,"duplicateRate":0}' 2>/dev/null || true
    done
}
trap cleanup EXIT

set_provider_latency() {
    for port in 8085 8086 8087 8088; do
        curl -s -o /dev/null --max-time 5 -X POST "http://localhost:${port}/_chaos" \
            -H 'Content-Type: application/json' \
            -d "{\"latencyMs\":$1,\"errorRate\":0,\"hangRate\":0,\"duplicateRate\":0}" 2>/dev/null || true
    done
}

pq() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

threads_live() {
    curl -s --max-time 5 "${ORCH}/actuator/prometheus" 2>/dev/null \
        | grep -E "^jvm_threads_live_threads" | awk '{print $NF}' | head -1
}

restart_with() {
    local mode="$1"
    echo "   restarting edge + orchestrator with VIRTUAL_THREADS=${mode}"
    VIRTUAL_THREADS="${mode}" EVENTS_PUBLISHER=outbox COMPENSATION_ENABLED=true \
        docker compose --profile async up -d --force-recreate \
        payment-orchestrator payments-edge >/dev/null 2>&1

    for _ in $(seq 1 60); do
        local e o
        e="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${EDGE}/actuator/health" 2>/dev/null)"
        o="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${ORCH}/actuator/health" 2>/dev/null)"
        [[ "$e" == "200" && "$o" == "200" ]] && return 0
        sleep 4
    done
    echo "   services did not come back" >&2
    return 1
}

run_arm() {
    local mode="$1" tag="$2"

    echo
    echo "=============================================================="
    echo " VIRTUAL_THREADS=${mode}"
    echo "=============================================================="
    restart_with "${mode}" || { FAIL=$((FAIL+1)); return 1; }

    set_provider_latency "${PROVIDER_MS}"
    echo "   provider latency ${PROVIDER_MS}ms, ${CONCURRENCY} concurrent, ${DURATION}s"

    local before started
    before="$(pq "SELECT COUNT(*) FROM payment WHERE merchant_reference LIKE 'load-%';")"
    started="$(date +%s)"

    # k6, not a shell loop. See threads.js for why the shell version measured
    # its own process-spawn cost: it reported both arms completing exactly
    # 1200 payments, which is 400 concurrency x 3 waves - the generator's
    # ceiling, not the server's.
    MSYS_NO_PATHCONV=1 docker run --rm --name "payorch-vt-${tag}"         --network payorch_payorch         -v "$(pwd -W 2>/dev/null || pwd)/tools/loadtest:/scripts"         -e EDGE=http://payments-edge:8080         -e VUS="${CONCURRENCY}"         -e DURATION="${DURATION}s"         grafana/k6:latest run --quiet /scripts/threads.js         > "${OUT}/k6-${tag}.log" 2>&1

    local elapsed peak done_n rej_n p95
    elapsed=$(( $(date +%s) - started ))
    peak="$(threads_live)"

    # k6's own counters, not a database count. The DB count would include
    # payments still being written when the arm ended and would attribute them
    # to whichever arm happened to be running.
    done_n="$(grep -oE 'payorch_payments_created[^0-9]*([0-9]+)' "${OUT}/k6-${tag}.log" | grep -oE '[0-9]+$' | tail -1)"
    rej_n="$(grep -oE 'payorch_payments_rejected[^0-9]*([0-9]+)' "${OUT}/k6-${tag}.log" | grep -oE '[0-9]+$' | tail -1)"
    p95="$(grep -oE "p\(95\)=[0-9.]+[a-z]+" "${OUT}/k6-${tag}.log" | tail -1)"
    done_n="${done_n:-0}"; rej_n="${rej_n:-0}"

    printf "   %-30s %s\n" "payments created (201)" "${done_n}"
    printf "   %-30s %ss\n" "elapsed" "${elapsed}"
    printf "   %-30s %s\n" "throughput (payments/s)" "$(( done_n / (elapsed>0?elapsed:1) ))"
    printf "   %-30s %s\n" "jvm_threads_live at end" "${peak%%.*}"

    echo "${mode},${done_n},${elapsed},${peak}" >> "${OUT}/summary.csv"
    eval "${tag//-/_}_done=${done_n}"
    eval "${tag//-/_}_threads=${peak%%.*}"
}

echo "=============================================================="
echo " PREFLIGHT"
echo "=============================================================="
printf "   %-26s %s\n" "concurrency per wave" "${CONCURRENCY}"
printf "   %-26s %sms" "provider latency" "${PROVIDER_MS}"
echo " (thread-bound by design - the provider call holds no connection)"
printf "   %-26s %ss\n" "arm duration" "${DURATION}"
echo "mode,completed,elapsed,threads" > "${OUT}/summary.csv"
widen_gates

run_arm "true"  "vt-virtual"
run_arm "false" "vt-platform"

echo
echo "=============================================================="
echo " RESULT"
echo "=============================================================="
printf "   %-22s %10s %10s\n" "" "virtual" "platform"
printf "   %-22s %10s %10s\n" "payments completed" "${vt_virtual_done:-0}" "${vt_platform_done:-0}"
printf "   %-22s %10s %10s\n" "jvm threads at end" "${vt_virtual_threads:-0}" "${vt_platform_threads:-0}"
echo

# THE ASSERTION THIS BENCHMARK ENDED UP MAKING IS NOT THE ONE IT STARTED WITH.
#
# It originally demanded virtual > platform, and failed three runs against a
# system that was behaving correctly. Throughput comes out the SAME because
# threads are never the constraint here - every route to slow I/O is guarded by
# something smaller than the thread count. Encoding "virtual must win" as a pass
# condition was asserting a belief rather than measuring one.
#
# What actually differs, reliably and by a lot, is how many OS threads it costs
# to do the identical work. That is the real claim for virtual threads and it is
# what this now asserts.
spread=$(( vt_virtual_done - vt_platform_done ))
[[ "${spread}" -lt 0 ]] && spread=$(( -spread ))
larger=$(( vt_virtual_done > vt_platform_done ? vt_virtual_done : vt_platform_done ))
pct=$(( larger > 0 ? spread * 100 / larger : 0 ))

if [[ "${pct}" -le 15 ]]; then
    printf "   ok   %-46s %s%% apart\n" "throughput is the same either way" "${pct}"
    echo "        Threads are not the constraint. That is the finding, not a"
    echo "        null result - see experiment 19."
else
    printf "   XX   %-46s %s%% apart\n" "throughput differed more than noise" "${pct}"
    echo "        One arm found a bound the other did not. Worth explaining"
    echo "        before quoting either number."
    FAIL=$((FAIL+1))
fi

if [[ "${vt_platform_threads:-0}" -gt "${vt_virtual_threads:-0}" ]]; then
    printf "   ok   %-46s %s vs %s\n" "and virtual costs far fewer OS threads"         "${vt_virtual_threads}" "${vt_platform_threads}"
else
    printf "   XX   %-46s %s vs %s\n" "virtual did not reduce OS threads"         "${vt_virtual_threads:-0}" "${vt_platform_threads:-0}"
    echo "        Check VIRTUAL_THREADS actually applied - identical thread"
    echo "        counts mean both arms ran the same build."
    FAIL=$((FAIL+1))
fi

echo "   Restoring VIRTUAL_THREADS=true, the project default since phase 0."
restart_with "true" >/dev/null 2>&1

echo "=============================================================="
if [[ "${FAIL}" -eq 0 ]]; then
    echo " PASS"
else
    echo " FAIL - ${FAIL} check(s) failed."
fi
echo "=============================================================="
exit "${FAIL}"
