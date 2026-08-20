#!/usr/bin/env bash
#
# Phase 7i: SIGTERM the orchestrator mid-payment. Does anything get lost?
#
#   tools/loadtest/graceful-drain.sh            # both arms, ~4 minutes
#   tools/loadtest/graceful-drain.sh drained    # arm A only, the 45s grace period
#   tools/loadtest/graceful-drain.sh cutoff     # arm B only, the old 10s one
#
# WHAT THE EXIT CRITERION ACTUALLY ASKS
#
# "In-flight requests complete or land in UNKNOWN; nothing lost." Note that
# UNKNOWN is an ACCEPTABLE outcome. The system is not required to finish every
# payment across a restart - it is required to never be unable to say what
# happened to one. A payment stranded in AUTHORIZING is the failure: the money
# may or may not have moved and nothing will ever resolve it, which is precisely
# what phase 3a built the UNKNOWN state to prevent.
#
# So the assertion is not "zero errors". It is "zero payments in a non-terminal,
# non-UNKNOWN state once the dust settles".
#
# THE TWO ARMS ARE ONE FLAG APART
#
# `docker stop -t N` overrides stop_grace_period for that one call, so the
# before and after differ by a single number and nothing else - no config edit,
# no rebuild, no restart of anything that is not being measured.
#
#   ARM A  -t 45   The configured grace period. Spring's 35s drain fits inside
#                  it, so the drain finishes and SIGKILL never arrives.
#
#   ARM B  -t 10   Docker's DEFAULT, which is what this project was running
#                  under until phase 7i. Spring begins a 35s drain and is killed
#                  at 10s. This arm exists because the fix is otherwise
#                  unfalsifiable: a green arm A proves the system survives a
#                  restart, not that the grace period is why.
#
# WHY THE PREFLIGHT IS THE INTERESTING PART OF THIS SCRIPT
#
# The bug 7i fixed was not in any code. Both halves were individually
# reasonable - `server.shutdown: graceful` in six services, and a compose file
# that said nothing about grace periods - and neither is expressed in terms of
# the other, so nothing anywhere was wrong enough to notice. The preflight below
# asserts the relationship rather than the values, which is the only form in
# which this class of bug is catchable.

set -uo pipefail

EDGE="${EDGE:-http://localhost:8080}"
ORCH="${ORCH:-http://localhost:8081}"
API_KEY="${API_KEY:-pk_test_dev_merchant_key}"
ARM="${1:-both}"

# Enough concurrent payments that some are certainly mid-flight when the signal
# lands. Not a load test - the question is what happens to the handful in the
# window, not how many the system can do.
N="${N:-40}"
CONCURRENCY="${CONCURRENCY:-12}"

CONTAINER="${CONTAINER:-payorch-payment-orchestrator}"

FAIL=0

# --------------------------------------------------------------- plumbing ---

pq() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

env_of() {
    docker inspect "$1" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
        | grep "^$2=" | cut -d= -f2
}

# stop_grace_period as Docker actually holds it, in seconds. Compose writes it
# to the container label; reading the label rather than the compose file is the
# point, because the running container is what will receive the signal.
grace_of() {
    docker inspect "$1" --format '{{index .Config.Labels "com.docker.compose.stop_grace_period"}}' \
        2>/dev/null | tr -d '\r'
}

chk() {
    if [[ "$2" == "$3" ]]; then
        printf "   ok   %-52s %s\n" "$1" "$2"
    else
        printf "   XX   %-52s %s (expected %s)\n" "$1" "$2" "$3"
        FAIL=$((FAIL+1))
    fi
}

note() {
    printf "   --   %-52s %s\n" "$1" "$2"
}

states() {
    pq "SELECT state, COUNT(*) FROM payment
        WHERE merchant_reference = '$1' GROUP BY state;" | tr '\t' '=' | tr '\n' ' '
}

count_in() {
    pq "SELECT COUNT(*) FROM payment
        WHERE merchant_reference = '$1' AND state IN ($2);"
}

# Payments that can never resolve themselves. THE number this experiment is
# about: not an error count, but a count of payments nobody can answer for.
stranded() {
    count_in "$1" "'INITIATED','ROUTED','AUTHORIZING'"
}

send_burst() {
    local tag="$1"
    for _ in $(seq 1 "$N"); do
        curl -s --max-time 60 -o /dev/null -X POST "${EDGE}/v1/payments" \
            -H "Content-Type: application/json" \
            -H "X-Api-Key: ${API_KEY}" \
            -H "Idempotency-Key: drain-$(date +%s%N)-$RANDOM" \
            -d "{\"amountMinor\":4200,\"currency\":\"INR\",\"card\":{\"number\":\"4242424242424242\",\"expiryMonth\":12,\"expiryYear\":2030,\"cvv\":\"123\"},\"merchantReference\":\"${tag}\"}" &
        while [[ "$(jobs -r | wc -l)" -ge "$CONCURRENCY" ]]; do wait -n; done
    done
}

wait_healthy() {
    echo -n "   waiting for ${CONTAINER} to come back"
    for _ in $(seq 1 60); do
        if curl -s -o /dev/null -w '%{http_code}' "${ORCH}/actuator/health" 2>/dev/null | grep -q 200; then
            echo " up"
            return 0
        fi
        echo -n "."
        sleep 2
    done
    echo " STILL DOWN"
    FAIL=$((FAIL+1))
}

# One arm: load, signal mid-flight, wait for the restart, count what is stuck.
run_arm() {
    local label="$1" timeout="$2" tag="$3"

    echo
    echo "=============================================================="
    echo " ${label} - docker stop -t ${timeout}"
    echo "=============================================================="

    send_burst "$tag" &
    local burst=$!

    # Long enough that requests are genuinely in flight and short enough that
    # the burst is not over. The whole experiment lives in this window.
    sleep 3

    echo "   SIGTERM -> ${CONTAINER} (grace ${timeout}s)"
    local stopped_at
    stopped_at="$(date +%s)"
    docker stop -t "${timeout}" "${CONTAINER}" >/dev/null 2>&1
    local stop_seconds=$(( $(date +%s) - stopped_at ))

    # Exit 137 is SIGKILL: Docker ran out of patience. 143 is a clean SIGTERM
    # exit, which is what a completed drain looks like from outside.
    local exit_code
    exit_code="$(docker inspect "${CONTAINER}" --format '{{.State.ExitCode}}' 2>/dev/null)"

    note "stop took" "${stop_seconds}s"
    note "container exit code" "${exit_code} $([[ "$exit_code" == "137" ]] && echo '(SIGKILL - drain was cut off)' || echo '(clean)')"

    docker start "${CONTAINER}" >/dev/null 2>&1
    wait "$burst" 2>/dev/null
    wait_healthy

    # Let the outbox relay and any in-flight resolution settle before counting.
    sleep 10

    local created stuck
    created="$(pq "SELECT COUNT(*) FROM payment WHERE merchant_reference = '${tag}';")"
    stuck="$(stranded "$tag")"

    note "payments created" "${created}"
    note "final states" "$(states "$tag")"
    chk "payments stranded in a non-terminal state" "${stuck}" "0"
}

# ------------------------------------------------------------ preflight -----

echo "=============================================================="
echo " PREFLIGHT - the timing chain"
echo "=============================================================="
echo "   The bug 7i fixed was not in any code. It was three numbers that were"
echo "   each reasonable and never compared to one another."
echo

budget_ms="$(env_of "${CONTAINER}" DEADLINE_BUDGET_MS)"
budget_ms="${budget_ms:-30000}"
drain="$(env_of "${CONTAINER}" SHUTDOWN_DRAIN)"
drain="${drain:-35s}"
grace="$(grace_of "${CONTAINER}")"

budget_s=$(( budget_ms / 1000 ))
drain_s="${drain%s}"
grace_s="${grace%s}"

printf "   %-34s %s\n" "DEADLINE_BUDGET_MS" "${budget_ms}ms (${budget_s}s)"
printf "   %-34s %s\n" "timeout-per-shutdown-phase" "${drain}"
printf "   %-34s %s\n" "stop_grace_period" "${grace:-UNSET - Docker default is 10s}"
echo

if [[ -z "$grace_s" ]]; then
    echo "   XX  stop_grace_period is unset, so Docker will SIGKILL at 10s while" >&2
    echo "       Spring is still draining. This is the phase-7i bug." >&2
    exit 2
fi

if [[ "$drain_s" -lt "$budget_s" ]]; then
    echo "   XX  the drain (${drain_s}s) is shorter than the longest a request may" >&2
    echo "       run (${budget_s}s). Requests inside their deadline will be cut off." >&2
    exit 2
fi

if [[ "$grace_s" -le "$drain_s" ]]; then
    echo "   XX  the grace period (${grace_s}s) does not exceed the drain (${drain_s}s)." >&2
    echo "       Docker will kill a server that is shutting down correctly." >&2
    exit 2
fi

echo "   ok   ${grace_s}s grace > ${drain_s}s drain >= ${budget_s}s request budget"

if ! curl -s -o /dev/null "${EDGE}/actuator/health"; then
    echo "refusing to run: the edge is not answering at ${EDGE}" >&2
    exit 2
fi

# ---------------------------------------------------------------- arms ------

[[ "$ARM" == "both" || "$ARM" == "drained" ]] && \
    run_arm "ARM A - THE CONFIGURED GRACE PERIOD" "$grace_s" "drain-ok"

if [[ "$ARM" == "both" || "$ARM" == "cutoff" ]]; then
    echo
    echo "   Arm B reproduces the pre-7i behaviour with a flag rather than a"
    echo "   config edit: -t 10 is Docker's default, which is what this project"
    echo "   was running under while six services claimed a graceful shutdown."
    run_arm "ARM B - THE OLD TEN-SECOND CUTOFF" 10 "drain-cutoff"
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
