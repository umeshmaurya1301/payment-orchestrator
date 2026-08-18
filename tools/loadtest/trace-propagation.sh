#!/usr/bin/env bash
#
# Phase 6g: does a trace survive the Kafka boundary?
#
#   tools/loadtest/trace-propagation.sh          # one payment, follow its trace
#   tools/loadtest/trace-propagation.sh 5        # five, for a less lucky sample
#
# THE QUESTION, AND WHY IT NEEDS AN EXPERIMENT RATHER THAN AN OPINION
#
# Every service here has had OpenTelemetry since phase 4 and the traces are
# genuinely good - as far as the last HTTP hop. The claim this phase makes is
# larger: that a payment authorized at 14:02:11 and an event consumed by the
# ledger at 14:02:14 - or at 14:12:14, if it took the ten-minute retry tier -
# are ONE trace, so that "what happened to this payment" is a single query
# rather than a join done by eye across two log searches.
#
# That does not happen for free. Nothing in Kafka carries a trace context unless
# somebody puts it in a header, and the outbox makes it harder than the usual
# case: the publish happens on a scheduler thread, minutes after the request
# thread that created the event has gone home. There is no ambient context left
# to propagate. It has to have been WRITTEN DOWN, in the row, inside the payment's
# transaction - which is the same argument the outbox itself makes about the
# event, applied to the trace.
#
# HOW THIS IS MEASURED WITHOUT GUESSING
#
# The usual way to check trace propagation is to make a request, find its trace
# in the UI, and look. That is a demo, not a measurement: it cannot be scripted,
# it cannot fail, and it proves one lucky case.
#
# So this script CHOOSES the trace id. A W3C `traceparent` header is generated
# here and sent with the payment. Spring's propagator continues an inbound trace
# rather than starting a new one, so every span and every log line downstream
# should carry a trace id this script already knows - no correlation, no
# searching, no ambiguity about which trace was "the right one".
#
# Then three independent sources are asked where that id appears:
#
#   1. container logs   - the JSON console output, MDC traceId. This is what a
#                         `docker compose logs | grep` during an incident sees.
#   2. SigNoz spans     - ClickHouse directly, grouped by service. This is the
#                         exit criterion: one trace, more than one service, and
#                         the async side present in it.
#   3. the ledger       - did the event actually arrive? Without this the whole
#                         thing can pass by consuming nothing at all.
#
# The third check is not decoration. A run where the event never reached the
# ledger would show zero ledger log lines for the trace id, which is exactly what
# a propagation failure looks like.

set -uo pipefail

EDGE="${EDGE:-http://localhost:8080}"
LEDGER="${LEDGER:-http://localhost:8084}"
API_KEY="${API_KEY:-pk_test_dev_merchant_key}"
N="${1:-1}"

CH="signoz-telemetrystore-clickhouse-0-0"
SERVICES="payments-edge payment-orchestrator psp-connector ledger-notifier"

FAIL=0

# --------------------------------------------------------------- plumbing ---

lq() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch_ledger -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

pq() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

# 32 hex characters, and not from $RANDOM: a trace id has to be unique against
# everything already in ClickHouse or the counts below are somebody else's.
new_trace_id() {
    python -c "import os;print(os.urandom(16).hex())"
}

new_span_id() {
    python -c "import os;print(os.urandom(8).hex())"
}

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

# Log lines from one container carrying a given string. --since keeps the scan
# bounded; the containers hold hours of earlier experiments.
log_hits() {
    docker logs --since 15m "payorch-$1" 2>&1 | grep -c "$2" | tr -d '\r'
}

ch() {
    docker exec "${CH}" clickhouse-client -q "$1" 2>/dev/null | tr -d '\r'
}

spans_by_service() {
    ch "SELECT \`resource_string_service\$\$name\`, count()
        FROM signoz_traces.distributed_signoz_index_v3
        WHERE trace_id = '$1'
        GROUP BY 1 ORDER BY 1
        FORMAT TabSeparated"
}

span_names() {
    ch "SELECT \`resource_string_service\$\$name\`, name, kind_string
        FROM signoz_traces.distributed_signoz_index_v3
        WHERE trace_id = '$1'
        ORDER BY timestamp
        FORMAT TabSeparated"
}

arm_seam() {
    curl -s -o /dev/null \
        -X POST "${LEDGER}/actuator/chaosseams/ledger-consumer" \
        -H 'content-type: application/json' \
        -d "{\"action\":\"FAIL\",\"probability\":$1}"
}

disarm_seam() {
    curl -s -o /dev/null -X DELETE "${LEDGER}/actuator/chaosseams"
}

injections() {
    curl -s "${LEDGER}/actuator/chaosseams" 2>/dev/null \
        | python -c "import json,sys;print(json.load(sys.stdin).get('injections',{}).get('ledger-consumer',0))" \
            2>/dev/null || echo 0
}

ledger_spans() {
    ch "SELECT count() FROM signoz_traces.distributed_signoz_index_v3
        WHERE trace_id = '$1'
          AND \`resource_string_service\$\$name\` = 'ledger-notifier'"
}

settle_spans() {
    local trace="$1" stable=0 last=-1
    for _ in $(seq 1 45); do
        local now
        now="$(ch "SELECT count() FROM signoz_traces.distributed_signoz_index_v3
                   WHERE trace_id = '${trace}'")"
        if [[ "${now}" == "${last}" && "${now}" != "0" ]]; then
            stable=$((stable + 1))
            [[ "${stable}" -ge 2 ]] && { echo "${now}"; return 0; }
        else
            stable=0
        fi
        last="${now}"
        echo -n "." >&2
        sleep 2
    done
    echo "${last}"
}

services_in_trace() {
    ch "SELECT countDistinct(\`resource_string_service\$\$name\`)
        FROM signoz_traces.distributed_signoz_index_v3
        WHERE trace_id = '$1'"
}

# ------------------------------------------------------------- preflight ----

echo "=============================================================="
echo " PREFLIGHT"
echo "=============================================================="

publisher="$(docker inspect payorch-payment-orchestrator \
    --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
    | grep '^EVENTS_PUBLISHER=' | cut -d= -f2)"
if [[ "${publisher}" != "outbox" ]]; then
    echo "   EVENTS_PUBLISHER is '${publisher:-unset}', not 'outbox'." >&2
    echo "   The whole point of this experiment is the context surviving a row" >&2
    echo "   in a table and a scheduler thread. Restart with:" >&2
    echo "     EVENTS_PUBLISHER=outbox docker compose --profile async up -d" >&2
    exit 2
fi
echo "   publisher                      outbox"

for c in mysql kafka-1 ledger-notifier payment-orchestrator payments-edge; do
    if ! docker ps --format '{{.Names}}' | grep -q "^payorch-${c}$"; then
        echo "   payorch-${c} is not running" >&2
        exit 2
    fi
done
echo "   stack                          up"

if [[ -z "$(ch 'SELECT 1')" ]]; then
    echo "   SigNoz's ClickHouse is not reachable (container ${CH})." >&2
    echo "   Run tools/obs/signoz.sh up && tools/obs/signoz.sh attach first -" >&2
    echo "   the log half of this experiment would still work, but the exit" >&2
    echo "   criterion is about a trace in SigNoz." >&2
    exit 2
fi
echo "   SigNoz ClickHouse              reachable"
echo

# ------------------------------------------------------------- the run ------

pass=0

for i in $(seq 1 "$N"); do

    TRACE_ID="$(new_trace_id)"
    SPAN_ID="$(new_span_id)"
    # -01: sampled. Without the flag the downstream sampler is free to drop
    # every span in the trace and the run would fail for a reason that has
    # nothing to do with Kafka.
    TRACEPARENT="00-${TRACE_ID}-${SPAN_ID}-01"

    echo "=============================================================="
    echo " PAYMENT ${i}/${N}"
    echo "=============================================================="
    echo "   traceparent   ${TRACEPARENT}"

    response="$(curl -s --max-time 30 -X POST "${EDGE}/v1/payments" \
        -H "Content-Type: application/json" \
        -H "X-Api-Key: ${API_KEY}" \
        -H "traceparent: ${TRACEPARENT}" \
        -H "Idempotency-Key: trace-$(date +%s%N)-$RANDOM" \
        -d '{"amountMinor":4200,"currency":"INR","card":{"number":"4242424242424242","expiryMonth":12,"expiryYear":2030,"cvv":"123"},"merchantReference":"trace-propagation"}')"

    payment_id="$(echo "${response}" | python -c "
import json,sys
try: print(json.load(sys.stdin).get('id',''))
except Exception: print('')
" 2>/dev/null)"

    if [[ -z "${payment_id}" ]]; then
        echo "   XX   no payment id in the response:"
        echo "        ${response}"
        FAIL=$((FAIL+1))
        continue
    fi
    echo "   payment       ${payment_id}"

    hex="$(echo "${payment_id}" | tr -d '-')"

    # Wait for the ledger to post it. The outbox poll is 500ms and the consumer
    # is immediate, so this is normally two or three seconds; the budget is
    # generous because a cold consumer group rebalance is slower.
    echo -n "   waiting for the ledger"
    posted=0
    for _ in $(seq 1 60); do
        posted="$(lq "SELECT COUNT(*) FROM ledger_entry WHERE payment_id = UNHEX('${hex}');")"
        [[ "${posted:-0}" -gt 0 ]] && break
        echo -n "."
        sleep 1
    done
    echo " ${posted:-0} entries"

    if [[ "${posted:-0}" -eq 0 ]]; then
        echo "   XX   the event never reached the ledger - nothing to trace"
        echo "        outbox pending: $(pq "SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL;")"
        FAIL=$((FAIL+1))
        continue
    fi

    # Spans are batched by the exporter and then batched again by the collector,
    # so reading ClickHouse immediately reports an empty trace for a trace that
    # is perfectly fine. Wait for the export rather than for a fixed sleep -
    # and wait for it to STOP GROWING, not for the first span to appear.
    #
    # The first version stopped at the first span and cost an hour: it reported
    # zero ledger spans for a trace whose ledger spans arrived four seconds
    # later, which reads exactly like a propagation failure. Two identical
    # counts, then stop.
    echo -n "   waiting for spans to settle"
    total="$(settle_spans "${TRACE_ID}")"
    echo " ${total} spans"

    echo
    echo "   WHERE THE TRACE ID APPEARS IN THE LOGS"
    printf "   %-24s %10s\n" "service" "log lines"
    ledger_lines=0
    for s in ${SERVICES}; do
        hits="$(log_hits "$s" "${TRACE_ID}")"
        printf "   %-24s %10s\n" "$s" "${hits}"
        [[ "$s" == "ledger-notifier" ]] && ledger_lines="${hits}"
    done

    echo
    echo "   SPANS IN SIGNOZ FOR THAT TRACE"
    by_service="$(spans_by_service "${TRACE_ID}")"
    if [[ -z "${by_service}" ]]; then
        echo "   (none)"
    else
        echo "${by_service}" | while IFS=$'\t' read -r svc n; do
            printf "   %-24s %10s\n" "${svc}" "${n}"
        done
    fi

    echo
    echo "   THE ASYNC SPANS, IF ANY"
    span_names "${TRACE_ID}" | grep -iE "ledger|kafka|payment.events|publish|receive|process" \
        | while IFS=$'\t' read -r svc name kind; do
            printf "     %-22s %-34s %s\n" "${svc}" "${name}" "${kind}"
        done

    n_services="$(services_in_trace "${TRACE_ID}")"

    echo
    chk_gt "log lines on the sync side" "$(log_hits payment-orchestrator "${TRACE_ID}")" 0
    chk_gt "log lines on the ASYNC side" "${ledger_lines}" 0
    chk_gt "services in the single trace" "${n_services:-0}" 2

    ledger_spans="$(ch "SELECT count() FROM signoz_traces.distributed_signoz_index_v3
        WHERE trace_id = '${TRACE_ID}'
          AND \`resource_string_service\$\$name\` = 'ledger-notifier'")"
    chk_gt "ledger-notifier spans in that trace" "${ledger_spans:-0}" 0

    [[ "${ledger_lines:-0}" -gt 0 && "${ledger_spans:-0}" -gt 0 ]] && pass=$((pass+1))
    echo
done

# --------------------------------------------- the ladder, still traced -----
#
# The reason this section exists: everything above proves the trace survives a
# HAPPY delivery, which is the case where somebody would have noticed it broken.
# The delivery worth tracing is the one that failed at 14:02 and succeeded at
# 14:12 from a retry topic, because that is the one where "what happened to this
# payment" is a genuinely hard question.
#
# Only tier 1 is exercised. The full ladder is 5s + 1m + 10m and
# tools/loadtest/retry-dlq.sh already walks it; what is under test here is
# whether the FORWARD to a retry topic carries the context, and the first hop
# answers that for the same reason the second and third would.

if [[ "${SKIP_LADDER:-0}" != "1" ]]; then

echo "=============================================================="
echo " THE RETRY LADDER, STILL IN THE SAME TRACE"
echo "=============================================================="
echo "   Hypothesis: a message that fails its first attempt and succeeds on the"
echo "   5-second tier produces TWO consumer spans in ONE trace - the attempt"
echo "   that failed and the redelivery that worked - rather than a trace that"
echo "   ends at the failure and a second trace nobody can find."
echo

TRACE_ID="$(new_trace_id)"
TRACEPARENT="00-${TRACE_ID}-$(new_span_id)-01"

before="$(injections)"
arm_seam 1.0
echo "   seam 'ledger-consumer' armed to FAIL with probability 1.0"
echo "   traceparent   ${TRACEPARENT}"

response="$(curl -s --max-time 30 -X POST "${EDGE}/v1/payments" \
    -H "Content-Type: application/json" \
    -H "X-Api-Key: ${API_KEY}" \
    -H "traceparent: ${TRACEPARENT}" \
    -H "Idempotency-Key: ladder-$(date +%s%N)-$RANDOM" \
    -d '{"amountMinor":4200,"currency":"INR","card":{"number":"4242424242424242","expiryMonth":12,"expiryYear":2030,"cvv":"123"},"merchantReference":"trace-ladder"}')"

payment_id="$(echo "${response}" | python -c "
import json,sys
try: print(json.load(sys.stdin).get('id',''))
except Exception: print('')
" 2>/dev/null)"
hex="$(echo "${payment_id}" | tr -d '-')"
echo "   payment       ${payment_id:-NONE}"

# Disarm the instant the first attempt has failed. Any later and the message
# takes tier 2, which is a sixty-second wait to learn nothing new.
echo -n "   waiting for the first attempt to fail"
for _ in $(seq 1 30); do
    [[ "$(injections)" -gt "${before}" ]] && break
    echo -n "."
    sleep 1
done
disarm_seam
echo " disarmed"

echo -n "   waiting for the 5-second tier to redeliver"
posted=0
for _ in $(seq 1 60); do
    posted="$(lq "SELECT COUNT(*) FROM ledger_entry WHERE payment_id = UNHEX('${hex}');")"
    [[ "${posted:-0}" -gt 0 ]] && break
    echo -n "."
    sleep 1
done
echo " ${posted:-0} entries"

echo -n "   waiting for spans to settle"
total="$(settle_spans "${TRACE_ID}")"
echo " ${total} spans"

echo
echo "   THE LEDGER'S SPANS IN THAT ONE TRACE"
span_names "${TRACE_ID}" | grep -i "ledger-notifier" \
    | while IFS=$'\t' read -r svc name kind; do
        printf "     %-22s %-40s %s\n" "${svc}" "${name}" "${kind}"
    done

retry_spans="$(ch "SELECT count() FROM signoz_traces.distributed_signoz_index_v3
    WHERE trace_id = '${TRACE_ID}' AND name LIKE '%retry-5000%'")"

echo
chk_gt "the event was eventually posted" "${posted:-0}" 0
chk_gt "consumer spans in the one trace" "$(ledger_spans "${TRACE_ID}")" 1
chk_gt "a span from the retry topic itself" "${retry_spans:-0}" 0
echo

fi

echo "=============================================================="
if [[ "${FAIL}" -eq 0 ]]; then
    echo " PASS - ${pass}/${N} payments produced one trace spanning the HTTP"
    echo "        path and the Kafka delivery."
else
    echo " FAIL - ${FAIL} check(s) failed. ${pass}/${N} payments crossed the"
    echo "        Kafka boundary with their trace intact."
fi
echo "=============================================================="
exit "${FAIL}"
