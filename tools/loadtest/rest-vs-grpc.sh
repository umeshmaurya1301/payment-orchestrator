#!/usr/bin/env bash
#
# Phase 9a: REST against gRPC on the connector hop, under identical load.
#
#   tools/loadtest/rest-vs-grpc.sh
#   VUS=200 DURATION=45 tools/loadtest/rest-vs-grpc.sh
#
# WHAT THE TRAP LIST SAYS, AND HOW THIS OBEYS IT
#
#   "Benchmarking gRPC against unoptimised REST. Use connection pooling and
#    keep-alive on both, or you are measuring TCP handshakes."
#
# RestClient pools by default and the gRPC channel is a singleton bean with
# keep-alive set - see OrchestratorConfiguration. Neither arm opens a connection
# per call, and the channel is deliberately NOT created per request, which would
# multiplex nothing and hand gRPC a handicap REST does not have.
#
#   "Assuming gRPC is always faster. For small payloads over a warm connection
#    the gap is often smaller than expected. Report what you measure, including
#    if it is unflattering - that is more credible, not less."
#
# So this script does not assert that gRPC wins. It asserts that both arms did
# the same amount of work and reports the difference, whatever its sign.
#
# WHAT THIS CANNOT MEASURE, AND WHY THAT IS THE POINT
#
# Experiment 19 established that this system is bounded by a bulkhead, an egress
# budget and a deadline slice long before it is bounded by anything about the
# transport. A payment does one connector call and several database round trips;
# swapping the encoding of that one call changes a small fraction of a small
# fraction. Expect the throughput columns to be close, and read the payload and
# CPU columns instead - PayloadSizeTest is where the deterministic part lives.

set -uo pipefail

# --------------------------------------------------------------------------
# THE FIRST RUN OF THIS SCRIPT WAS INVALID, AND THE k6 LOG SAID SO
#
# It reported REST p95 25ms against gRPC p95 18ms and I nearly wrote that down.
# Then the log:
#
#     http_reqs .......... 457414   10151/s
#     http_req_failed .... 99.49%
#     payments created ... 2347     (52/s)
#
# Ten thousand requests a second, of which fifty-two got through. The other
# 99.5% were 429s from the edge limiter, answered in a millisecond without ever
# reaching a connector - so the p95 being compared was the p95 of REJECTIONS,
# and rejections are byte-identical on both arms. The transport was roughly 0.5%
# of what was being measured.
#
# virtual-vs-platform.sh already knew this and widens the gates first. This
# script did not, which is the same mistake in a new costume: experiment 19's
# first version measured its own load generator, and this one measured its own
# rate limiter. Both produce a confident number about the wrong subsystem.
#
# So: widen the gates, and ASSERT the rejection rate is low rather than trusting
# that it is. An unvalidated benchmark that silently degrades into measuring a
# limiter is worse than no benchmark, because it still prints a table.
# --------------------------------------------------------------------------

# THE SECOND THING THE FIRST RUN GOT WRONG
#
# Both arms reported 52 payments/s and I read that as "the transport does not
# matter". It is not a finding, it is application.yml:
#
#     merchant-per-sec: ${RATELIMIT_MERCHANT_RPS:50}
#
# Fifty. The two arms agreed to within 0.1% because a token bucket three
# services upstream of the connector decided the number before either transport
# was reached. Any two builds compared this way agree, including a build
# compared against itself with a deliberate 200ms sleep inserted.
#
# So the edge limits come up for the duration, and go back down afterwards.
# Widened - not disabled: 3e's own comment records that this service dies at
# 500 rps, and an unlimited arm would measure the OOM.

widen_edge() {
    echo "   raising the edge limits (merchant 1000/s, write 1000/s) for the run"
    RATELIMIT_MERCHANT_RPS=1000 RATELIMIT_MERCHANT_BURST=2000 \
    RATELIMIT_WRITE_RPS=1000 RATELIMIT_WRITE_BURST=2000 \
        docker compose --profile async up -d --force-recreate payments-edge >/dev/null 2>&1
    wait_for "${EDGE}/actuator/health"
}

restore_edge() {
    echo "   restoring the edge limits to their configured defaults"
    docker compose --profile async up -d --force-recreate payments-edge >/dev/null 2>&1
    wait_for "${EDGE}/actuator/health"
}

wait_for() {
    for _ in $(seq 1 60); do
        [[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$1")" == "200" ]] && return 0
        sleep 4
    done
    return 1
}

GATES_BACKUP="${OUT:-tools/loadtest/results/09-rest-vs-grpc}/psp_config.bak"

widen_gates() {
    mkdir -p "$(dirname "${GATES_BACKUP}")"
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B \
        -e "SELECT psp_id, bulkhead_max_concurrent, egress_tps, deadline_slice_ms FROM psp_config;" \
        2>/dev/null | tr -d '\r' > "${GATES_BACKUP}"
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch \
        -e "UPDATE psp_config SET bulkhead_max_concurrent=2000, egress_tps=5000, deadline_slice_ms=30000;" \
        2>/dev/null
    echo "   gates widened (bulkhead 2000, egress 5000/s, slice 30s); originals in ${GATES_BACKUP}"
}

restore_gates() {
    [[ -s "${GATES_BACKUP}" ]] || return 0
    while read -r id bh tps slice; do
        [[ -z "${id}" ]] && continue
        docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch \
            -e "UPDATE psp_config SET bulkhead_max_concurrent=${bh}, egress_tps=${tps}, deadline_slice_ms=${slice} WHERE psp_id='${id}';" \
            2>/dev/null
    done < "${GATES_BACKUP}"
    echo "   gates restored from ${GATES_BACKUP}"
}

EDGE="${EDGE:-http://localhost:8080}"
ORCH="${ORCH:-http://localhost:8081}"
# 30, not 100. Phase 3d measured this edge dying of OutOfMemoryError at 500 rps,
# so a benchmark that removes the limiter and points 100 VUs at it measures a
# crash. 30 in-flight is comfortably inside what the stack sustains and still far
# above the one-at-a-time case where a transport difference could not show.
VUS="${VUS:-30}"
DURATION="${DURATION:-45}"
OUT="${OUT:-tools/loadtest/results/09-rest-vs-grpc}"
FAIL=0

mkdir -p "${OUT}"

pq() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

restart_with() {
    local transport="$1"
    echo "   restarting the orchestrator with CONNECTOR_TRANSPORT=${transport}"
    CONNECTOR_TRANSPORT="${transport}" EVENTS_PUBLISHER=outbox COMPENSATION_ENABLED=true \
    UNKNOWN_POLLER_ENABLED=true GRPC_SERVER_ENABLED=true \
        docker compose --profile async up -d --force-recreate payment-orchestrator >/dev/null 2>&1
    for _ in $(seq 1 60); do
        [[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${ORCH}/actuator/health")" == "200" ]] && {
            # A warm-up call before the clock starts. The first request on a
            # cold channel pays for connection setup and class loading in both
            # arms, and at these durations that is a measurable fraction.
            curl -s -o /dev/null --max-time 30 -X POST "${EDGE}/v1/payments" \
                -H "Content-Type: application/json" -H "X-Api-Key: pk_test_dev_merchant_key" \
                -H "Idempotency-Key: warm-$(date +%s%N)" \
                -d '{"amountMinor":4200,"currency":"INR","card":{"number":"4242424242424242","expiryMonth":12,"expiryYear":2030,"cvv":"123"},"merchantReference":"warmup"}' 2>/dev/null
            return 0
        }
        sleep 4
    done
    echo "   the orchestrator did not come back" >&2
    return 1
}

# Mean CPU% of the two services on the hop, sampled while the load runs.
# `docker stats --no-stream` is a point sample, so this averages several.
sample_cpu() {
    local tag="$1" seconds="$2" n=0 total=0
    local deadline=$(( $(date +%s) + seconds ))
    while [[ "$(date +%s)" -lt "${deadline}" ]]; do
        local s
        s="$(docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' \
             payorch-payment-orchestrator payorch-psp-connector 2>/dev/null \
             | awk '{gsub(/%/,"",$2); sum+=$2} END {print sum+0}')"
        [[ -n "${s}" ]] && { total=$(python -c "print(${total}+${s})"); n=$((n+1)); }
        sleep 3
    done
    [[ "${n}" -gt 0 ]] && python -c "print('%.1f' % (${total}/${n}))" || echo "0"
}

run_arm() {
    local transport="$1" tag="$2"

    echo
    echo "=============================================================="
    echo " CONNECTOR_TRANSPORT=${transport}"
    echo "=============================================================="
    restart_with "${transport}" || { FAIL=$((FAIL+1)); return 1; }

    local before
    before="$(pq "SELECT COUNT(*) FROM payment;")"

    sample_cpu "${tag}" "${DURATION}" > "${OUT}/cpu-${tag}.txt" &
    local cpu_pid=$!

    MSYS_NO_PATHCONV=1 docker run --rm --name "payorch-tx-${tag}" \
        --network payorch_payorch \
        -v "$(pwd -W 2>/dev/null || pwd)/tools/loadtest:/scripts" \
        -e EDGE=http://payments-edge:8080 \
        -e VUS="${VUS}" \
        -e DURATION="${DURATION}s" \
        grafana/k6:latest run --quiet /scripts/threads.js \
        > "${OUT}/k6-${tag}.log" 2>&1

    wait "${cpu_pid}" 2>/dev/null
    sleep 4

    local after created p95 cpu
    after="$(pq "SELECT COUNT(*) FROM payment;")"
    created=$(( after - before ))
    p95="$(grep -oE 'p\(95\)=[0-9.]+[a-z]+' "${OUT}/k6-${tag}.log" | tail -1 | sed 's/p(95)=//')"
    cpu="$(cat "${OUT}/cpu-${tag}.txt" 2>/dev/null)"

    # The validity check. threads.js counts 201s and non-201s separately for
    # exactly this reason: a run where most requests bounced off the limiter is
    # not a measurement of the transport, however confident its table looks.
    local ok rej share
    ok="$(grep -oE 'payorch_payments_created[.: ]+[0-9]+' "${OUT}/k6-${tag}.log" | grep -oE '[0-9]+$')"
    rej="$(grep -oE 'payorch_payments_rejected[.: ]+[0-9]+' "${OUT}/k6-${tag}.log" | grep -oE '[0-9]+$')"
    ok="${ok:-0}"; rej="${rej:-0}"
    share=$(( (ok + rej) > 0 ? ok * 100 / (ok + rej) : 0 ))

    printf "   %-28s %s\n" "payments created" "${created}"
    printf "   %-28s %s\n" "throughput (per second)" "$(( created / DURATION ))"
    printf "   %-28s %s\n" "http_req_duration p95" "${p95:-n/a}"
    printf "   %-28s %s%%\n" "mean CPU (orch+connector)" "${cpu:-n/a}"
    printf "   %-28s %s%% (%s of %s reached a connector)\n" "admitted" "${share}" "${ok}" "$(( ok + rej ))"

    if [[ "${share}" -lt 50 ]]; then
        echo "   XX   ${transport}: only ${share}% of requests were admitted - this"
        echo "        arm measured the limiter, not the transport. See the header."
        FAIL=$((FAIL+1))
    fi

    eval "${tag}_created=${created}"
    eval "${tag}_p95='${p95:-n/a}'"
    eval "${tag}_cpu='${cpu:-0}'"
}

echo "=============================================================="
echo " PREFLIGHT"
echo "=============================================================="
printf "   %-28s %s\n" "concurrent VUs" "${VUS}"
printf "   %-28s %ss\n" "per arm" "${DURATION}"
printf "   %-28s %s\n" "payload sizes" "see PayloadSizeTest - deterministic, not measured here"

widen_gates
widen_edge
trap 'restore_gates; restore_edge' EXIT

run_arm "rest" "rest"
run_arm "grpc" "grpc"

echo
echo "=============================================================="
echo " RESULT"
echo "=============================================================="
printf "   %-24s %12s %12s\n" "" "REST" "gRPC"
printf "   %-24s %12s %12s\n" "payments created" "${rest_created:-0}" "${grpc_created:-0}"
printf "   %-24s %12s %12s\n" "p95" "${rest_p95:-n/a}" "${grpc_p95:-n/a}"
printf "   %-24s %11s%% %11s%%\n" "mean CPU" "${rest_cpu:-0}" "${grpc_cpu:-0}"
echo

# Both arms must have done comparable work, or the columns are not comparable.
# This is the assertion; which transport won is a REPORTED number, not a
# required one - see the trap note at the top.
spread=$(( rest_created - grpc_created ))
[[ "${spread}" -lt 0 ]] && spread=$(( -spread ))
larger=$(( rest_created > grpc_created ? rest_created : grpc_created ))
pct=$(( larger > 0 ? spread * 100 / larger : 0 ))

if [[ "${larger}" -gt 0 ]]; then
    printf "   ok   %-46s %s\n" "both arms carried real traffic" "${larger}"
else
    printf "   XX   %-46s %s\n" "neither arm created any payments" "0"
    FAIL=$((FAIL+1))
fi

printf "   --   %-46s %s%%\n" "throughput difference" "${pct}"
# No "that is just noise" verdict here, because this script cannot tell. A
# single pair of runs has no error bar: the first version of this benchmark
# reported a 27% p95 gap that vanished completely on the second run, and the
# same version reported a 0% throughput gap that turned out to be a rate limit
# rather than a result. One run distinguishes neither case.
#
# Run it twice. Two pairs agreeing to within a percent is the evidence; one pair
# is an anecdote with a table around it.
echo "        Run this twice before believing the sign. See docs/experiments/23."

echo
echo "=============================================================="
[[ "${FAIL}" -eq 0 ]] && echo " PASS" || echo " FAIL - ${FAIL} check(s) failed."
echo "=============================================================="

echo
echo "   Restoring CONNECTOR_TRANSPORT=rest, the default."
restart_with "rest" >/dev/null 2>&1
exit "${FAIL}"
