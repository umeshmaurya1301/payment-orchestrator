#!/usr/bin/env bash
#
# Phase 9a item 4: a server stream against the polling loop it replaces.
#
#   tools/loadtest/watch-vs-poll.sh
#
# WHAT IS BEING COMPARED
#
# The SAME merchant-facing endpoint - GET /v1/payments/{id}/events, Server-Sent
# Events - backed by two different implementations of OrchestratorClient.watch:
#
#   REST arm   the edge polls GET /internal/v1/payments/{id} in a loop
#   gRPC arm   the edge opens ONE server stream and the orchestrator watches
#
# The merchant sees the same thing either way. What differs is what it costs
# behind the edge, and that is the only claim server streaming makes.
#
# THE INTERVAL IS HELD EQUAL, AND THAT IS NOT A DETAIL
#
# Both arms check every 250ms. A stream checking every 200ms against a client
# polling every 2s would report a latency win that is entirely the interval -
# and this project has made that exact error twice already: experiment 19
# measured its own load generator, experiment 23 measured a rate limiter. Both
# printed confident tables about the wrong subsystem.
#
# WHAT THIS CANNOT SHOW
#
# Streaming does not remove polling from the system, it MOVES it: the
# orchestrator watches by re-reading, because the state machine has no change
# feed. One loop in one service replaces N loops across N clients. That is a
# real reduction and it is not the same as none, so the expected result is
# "fewer requests on the internal hop", not "no polling anywhere".

set -uo pipefail

EDGE="${EDGE:-http://localhost:8080}"
ORCH="${ORCH:-http://localhost:8081}"
WATCH_SECONDS="${WATCH_SECONDS:-10}"
INTERVAL_MS="${INTERVAL_MS:-250}"
CAPTURE_AFTER="${CAPTURE_AFTER:-4}"
OUT="${OUT:-tools/loadtest/results/09-watch-vs-poll}"
FAIL=0

mkdir -p "${OUT}"

# COUNT of GETs the orchestrator has served on the payment-by-id endpoint. This
# is the number the whole experiment turns on: the REST arm drives it up once
# per poll, the gRPC arm should barely move it.
orch_gets() {
    curl -s --max-time 10 \
        "${ORCH}/actuator/metrics/http.server.requests?tag=uri:/internal/v1/payments/%7Bid%7D&tag=method:GET" \
        2>/dev/null \
        | python -c "import json,sys
try:
    d=json.load(sys.stdin)
    print(int([m['value'] for m in d['measurements'] if m['statistic']=='COUNT'][0]))
except Exception:
    print(0)"
}

restart_edge() {
    local transport="$1"
    echo "   restarting the edge with ORCHESTRATOR_TRANSPORT=${transport}"
    ORCHESTRATOR_TRANSPORT="${transport}" \
        docker compose --profile async up -d --force-recreate payments-edge >/dev/null 2>&1
    for _ in $(seq 1 40); do
        [[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${EDGE}/actuator/health")" == "200" ]] \
            && return 0
        sleep 3
    done
    echo "   the edge did not come back" >&2
    return 1
}

create_payment() {
    curl -s --max-time 30 -X POST "${EDGE}/v1/payments" \
        -H "Content-Type: application/json" \
        -H "X-Api-Key: pk_test_dev_merchant_key" \
        -H "Idempotency-Key: watch-$(date +%s%N)" \
        -d '{"amountMinor":4200,"currency":"INR","card":{"number":"4242424242424242","expiryMonth":12,"expiryYear":2030,"cvv":"123"},"merchantReference":"watch-vs-poll"}' \
        | grep -oE '"id":"[^"]+' | head -1 | cut -d'"' -f4
}

run_arm() {
    local transport="$1" tag="$2"

    echo
    echo "=============================================================="
    echo " ORCHESTRATOR_TRANSPORT=${transport}"
    echo "=============================================================="
    restart_edge "${transport}" || { FAIL=$((FAIL+1)); return 1; }

    local id
    id="$(create_payment)"
    if [[ -z "${id}" ]]; then
        printf "   XX   %-50s\n" "could not create a payment"
        FAIL=$((FAIL+1))
        return 1
    fi
    printf "   %-52s %s\n" "payment" "${id}"

    # The baseline is taken AFTER creating the payment and AFTER the endpoint's
    # own ownership check would run, so the count reflects the watch and not the
    # setup around it.
    sleep 1
    local before
    before="$(orch_gets)"

    # Capture partway through, from a separate process, so the stream has to
    # notice a change it did not cause.
    ( sleep "${CAPTURE_AFTER}"; curl -s -o /dev/null --max-time 20 \
        -X POST "${EDGE}/v1/payments/${id}/capture" \
        -H "X-Api-Key: pk_test_dev_merchant_key" ) &
    local capture_pid=$!

    local start
    start="$(date +%s%N)"
    timeout "$(( WATCH_SECONDS + 8 ))" curl -sN --max-time "$(( WATCH_SECONDS + 6 ))" \
        "${EDGE}/v1/payments/${id}/events?seconds=${WATCH_SECONDS}&intervalMs=${INTERVAL_MS}" \
        -H "X-Api-Key: pk_test_dev_merchant_key" \
        > "${OUT}/sse-${tag}.txt" 2>&1
    local elapsed=$(( ( $(date +%s%N) - start ) / 1000000 ))

    wait "${capture_pid}" 2>/dev/null
    sleep 1

    local after gets frames states
    after="$(orch_gets)"
    gets=$(( after - before ))
    frames="$(grep -c '^event:' "${OUT}/sse-${tag}.txt" 2>/dev/null)"
    frames="${frames:-0}"
    states="$(grep '^event:' "${OUT}/sse-${tag}.txt" 2>/dev/null | sed 's/^event://' | tr '\n' ' ')"

    printf "   %-52s %s\n" "GETs served by the orchestrator during the watch" "${gets}"
    printf "   %-52s %s\n" "SSE frames the merchant received" "${frames}"
    printf "   %-52s %s\n" "states" "${states}"
    printf "   %-52s %sms\n" "stream ended after" "${elapsed}"

    eval "${tag}_gets=${gets}"
    eval "${tag}_frames=${frames}"
    eval "${tag}_elapsed=${elapsed}"
    eval "${tag}_states=\"${states}\""
}

echo "=============================================================="
echo " PREFLIGHT"
echo "=============================================================="
printf "   %-52s %ss\n" "watch budget" "${WATCH_SECONDS}"
printf "   %-52s %sms\n" "interval, HELD EQUAL on both arms" "${INTERVAL_MS}"
printf "   %-52s %ss\n" "capture fires after" "${CAPTURE_AFTER}"

run_arm "rest" "rest"
run_arm "grpc" "grpc"

echo
echo "=============================================================="
echo " RESULT"
echo "=============================================================="
printf "   %-32s %12s %12s\n" "" "REST (poll)" "gRPC (stream)"
printf "   %-32s %12s %12s\n" "orchestrator GETs" "${rest_gets:-0}" "${grpc_gets:-0}"
printf "   %-32s %12s %12s\n" "SSE frames delivered" "${rest_frames:-0}" "${grpc_frames:-0}"
printf "   %-32s %11sms %11sms\n" "stream ended after" "${rest_elapsed:-0}" "${grpc_elapsed:-0}"
echo

# Both arms must have delivered the same thing to the merchant. If they have
# not, the cost comparison below is between two different behaviours and means
# nothing - which is the failure mode of every "we replaced X with Y" benchmark.
if [[ "${rest_states:-}" == "${grpc_states:-}" && -n "${rest_states:-}" ]]; then
    printf "   ok   %-50s %s\n" "both arms delivered the same states" "${rest_states}"
else
    printf "   XX   %-50s\n" "the arms delivered DIFFERENT states"
    printf "        rest: %s\n" "${rest_states:-none}"
    printf "        grpc: %s\n" "${grpc_states:-none}"
    FAIL=$((FAIL+1))
fi

# The claim. Not "gRPC is faster" - the merchant sees the same latency, because
# both check at the same interval - but "the internal hop carries far less".
if [[ "${grpc_gets:-999}" -lt "${rest_gets:-0}" ]]; then
    printf "   ok   %-50s %s -> %s\n" "the stream cost fewer internal requests" \
        "${rest_gets}" "${grpc_gets}"
else
    printf "   XX   %-50s %s vs %s\n" "the stream did NOT reduce internal requests" \
        "${rest_gets:-0}" "${grpc_gets:-0}"
    FAIL=$((FAIL+1))
fi

echo
echo "   The orchestrator still polls its own database on the gRPC arm - the"
echo "   state machine has no change feed. What disappeared is the request per"
echo "   poll across the edge: API-key lookup, rate-limit token, deadline scope,"
echo "   HTTP round trip and JSON parse, all of it repeated to be told nothing"
echo "   changed."

echo
echo "   Restoring ORCHESTRATOR_TRANSPORT=rest, the default."
restart_edge "rest" >/dev/null 2>&1

echo
echo "=============================================================="
[[ "${FAIL}" -eq 0 ]] && echo " PASS" || echo " FAIL - ${FAIL} check(s) failed."
echo "=============================================================="
exit "${FAIL}"
