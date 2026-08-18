#!/usr/bin/env bash
#
# Phase 6h: what does signing a webhook actually buy, and what does it not?
#
#   tools/loadtest/webhook-security.sh
#
# THE QUESTION
#
# "Webhooks are HMAC signed with timestamp replay protection" is a sentence
# everyone writes and almost nobody measures. It bundles together three separate
# guarantees, and one of them is not a guarantee at all:
#
#   authenticity   the request came from the holder of the secret
#   integrity      the body has not been altered
#   freshness      it was sent recently
#
# Freshness is the one that gets oversold. A tolerance window BOUNDS a replay; it
# does not prevent one. Inside the window a captured delivery is a genuinely
# valid request - correct MAC, correct body, correct timestamp - and nothing in
# the signature scheme refuses it. Only the receiver deduplicating on the event id
# does, and that is not part of signing at all.
#
# So this script measures all three, separately, and includes an arm that shows
# the signed-and-fresh replay being ACCEPTED. That arm is the point.
#
# FOUR ARMS
#
#   A  unsigned            what an unsigned endpoint costs, in forged money
#   B  signed, 300s        forgery and tampering refused; a fresh replay accepted
#   C  signed, 2s          the same captured delivery, refused as stale
#   D  signed + dedupe     the same captured delivery, refused as a duplicate
#
# The receiver is docker/webhook-sink/sink.py, written in Python from the header
# format rather than from WebhookSigner.java, deliberately: two halves of one
# codebase sharing one helper always agree, including when both are wrong. If
# this run passes, an independent implementation accepts our signatures, which is
# the only version of "the scheme works" that means anything.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EDGE="${EDGE:-http://localhost:8080}"
SINK="${SINK:-http://localhost:9096}"
LEDGER="${LEDGER:-http://localhost:8084}"
API_KEY="${API_KEY:-pk_test_dev_merchant_key}"

# Local development value, public by design, like every other secret in this
# repo until phase 9c.
SECRET="${WEBHOOK_SECRET:-whsec_local_dev_only}"

FAIL=0

# --------------------------------------------------------------- plumbing ---

compose() {
    # The obs override only if SigNoz is actually up. The trace assertion is
    # skipped without it rather than failing the run - the security claims are
    # what this experiment is for, and they do not need a collector.
    if docker network inspect signoz-network >/dev/null 2>&1; then
        docker compose -f "${ROOT}/docker-compose.yml" \
                       -f "${ROOT}/docker/signoz/payorch-obs.override.yml" \
                       --profile async "$@"
    else
        docker compose -f "${ROOT}/docker-compose.yml" --profile async "$@"
    fi
}

sink_state() {
    curl -s --max-time 5 "${SINK}/" 2>/dev/null
}

sink_reset() {
    curl -s -o /dev/null -X DELETE "${SINK}/" 2>/dev/null
}

sink_field() {
    sink_state | python -c "
import json,sys
try: print(json.load(sys.stdin).get('$1'))
except Exception: print('?')
" 2>/dev/null
}

sink_rejected_by() {
    sink_state | python -c "
import json,sys
try: print(json.load(sys.stdin).get('rejectedBy',{}).get('$1',0))
except Exception: print(0)
" 2>/dev/null
}

# Restart BOTH containers, because the sink reads its env at process start and
# the ledger reads whether to sign at context start. A run that only restarted
# one would measure a sender and a receiver that disagree about which arm they
# are in, and the result would look like a signature bug.
restart_stack() {
    local secret="$1" tolerance="$2" dedupe="$3" sign="$4"
    WEBHOOK_SECRET="${secret}" \
    WEBHOOK_TOLERANCE="${tolerance}" \
    WEBHOOK_DEDUPE="${dedupe}" \
    WEBHOOKS_ENABLED=true \
    WEBHOOKS_SIGN="${sign}" \
    EVENTS_PUBLISHER=outbox \
        compose up -d --force-recreate webhook-sink ledger-notifier >/dev/null 2>&1

    echo -n "   waiting for the receiver and the sender"
    for _ in $(seq 1 60); do
        if [[ -n "$(sink_state)" ]] \
           && [[ "$(curl -s -o /dev/null -w '%{http_code}' "${LEDGER}/actuator/health")" == "200" ]]; then
            echo " up"
            sink_reset
            return 0
        fi
        echo -n "."
        sleep 2
    done
    echo " TIMED OUT"
    FAIL=$((FAIL+1))
    return 1
}

send_payment() {
    curl -s --max-time 30 -X POST "${EDGE}/v1/payments" \
        -H "Content-Type: application/json" \
        -H "X-Api-Key: ${API_KEY}" \
        ${1:+-H "traceparent: $1"} \
        -H "Idempotency-Key: wh-$(date +%s%N)-$RANDOM" \
        -d '{"amountMinor":4200,"currency":"INR","card":{"number":"4242424242424242","expiryMonth":12,"expiryYear":2030,"cvv":"123"},"merchantReference":"webhook-security"}' \
        | python -c "
import json,sys
try: print(json.load(sys.stdin).get('id',''))
except Exception: print('')
" 2>/dev/null
}

# Waits for the sink's total to move. Polling the COUNT rather than sleeping,
# because the path is edge -> orchestrator -> outbox -> relay -> Kafka ->
# consumer -> webhook and its latency is the sum of six components.
wait_for_delivery() {
    local before="$1"
    for _ in $(seq 1 40); do
        local now
        now=$(( $(sink_field accepted) + $(sink_field rejected) ))
        [[ "${now}" -gt "${before}" ]] && return 0
        sleep 1
    done
    return 1
}

total_deliveries() {
    echo $(( $(sink_field accepted) + $(sink_field rejected) ))
}

# The last delivery the sink accepted, as a shell-evaluable capture. This is what
# an attacker with access to the wire has: the exact bytes and the exact header.
capture_last_accepted() {
    sink_state | python -c "
import json,sys
d = json.load(sys.stdin)
for record in reversed(d.get('deliveries', [])):
    if record.get('accepted'):
        print(json.dumps({'body': record.get('body') or '',
                          'signature': record.get('signature') or '',
                          'eventId': record.get('eventId') or ''}))
        break
" 2>/dev/null
}

# POSTs a body with optional signature and event id, and prints the HTTP status.
post_webhook() {
    local body="$1" signature="${2:-}" event_id="${3:-}"
    curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
        -X POST "${SINK}/" \
        -H "Content-Type: application/json" \
        ${signature:+-H "X-Payorch-Signature: ${signature}"} \
        ${event_id:+-H "X-Payorch-Event-Id: ${event_id}"} \
        --data-binary "${body}"
}

sign_body() {
    local secret="$1" timestamp="$2" body="$3"
    python -c "
import hashlib, hmac, sys
secret, ts, body = sys.argv[1], sys.argv[2], sys.argv[3]
mac = hmac.new(secret.encode(), (ts + '.').encode() + body.encode(), hashlib.sha256)
print('t=' + ts + ',v1=' + mac.hexdigest())
" "${secret}" "${timestamp}" "${body}"
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

FORGED_BODY='{"id":"00000000-0000-0000-0000-0000deadbeef","type":"payment.authorized","createdAt":"2026-08-18T14:00:00Z","data":{"paymentId":"00000000-0000-0000-0000-00000000dead","merchantId":"0192abcd-0000-7000-8000-000000000001","state":"AUTHORIZED","amountMinor":50000000,"currency":"INR","pspId":"psp-a","cardBin":"424242","cardLast4":"4242"}}'

# ------------------------------------------------------------- preflight ----

echo "=============================================================="
echo " PREFLIGHT"
echo "=============================================================="

publisher="$(docker inspect payorch-payment-orchestrator \
    --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
    | grep '^EVENTS_PUBLISHER=' | cut -d= -f2)"
if [[ "${publisher}" != "outbox" ]]; then
    echo "   EVENTS_PUBLISHER is '${publisher:-unset}', not 'outbox' - no events," >&2
    echo "   so no webhooks. Restart with:" >&2
    echo "     EVENTS_PUBLISHER=outbox docker compose --profile async up -d" >&2
    exit 2
fi
echo "   publisher                      outbox"
echo "   forged amount                  50000000 minor (INR 500,000)"
echo

# ===================================================================== A =====

echo "=============================================================="
echo " ARM A - UNSIGNED"
echo "=============================================================="
echo "   Hypothesis: the receiver cannot tell our webhook from anyone else's,"
echo "   so a forged AUTHORIZED for a payment that never existed is accepted,"
echo "   and a captured one can be replayed as many times as you like."
echo

restart_stack "" 300 false false

before="$(total_deliveries)"
payment="$(send_payment)"
echo "   real payment  ${payment:-NONE}"
wait_for_delivery "${before}" || echo "   (no delivery arrived)"
chk_gt "genuine webhooks accepted" "$(sink_field accepted)" 0

forged_status="$(post_webhook "${FORGED_BODY}")"
chk "a forged INR 500,000 authorization" "${forged_status}" "200"

capture="$(capture_last_accepted)"
body="$(echo "${capture}" | python -c "import json,sys;print(json.load(sys.stdin)['body'])" 2>/dev/null)"
event_id="$(echo "${capture}" | python -c "import json,sys;print(json.load(sys.stdin)['eventId'])" 2>/dev/null)"

replayed=0
for _ in 1 2 3 4 5; do
    [[ "$(post_webhook "${body}" "" "${event_id}")" == "200" ]] && replayed=$((replayed+1))
done
chk "a captured delivery replayed five times" "${replayed}" "5"

echo
printf "   %-24s %s accepted, %s rejected\n" "sink totals" \
    "$(sink_field accepted)" "$(sink_field rejected)"
echo

# ===================================================================== B =====

echo "=============================================================="
echo " ARM B - SIGNED, 300s TOLERANCE"
echo "=============================================================="
echo "   Hypothesis: forgery and tampering are refused. A REPLAY of a genuine"
echo "   delivery is ACCEPTED, because everything about it is genuine - which"
echo "   is the finding, not a defect."
echo

restart_stack "${SECRET}" 300 false true

before="$(total_deliveries)"
payment="$(send_payment)"
echo "   real payment  ${payment:-NONE}"
wait_for_delivery "${before}" || echo "   (no delivery arrived)"

chk_gt "our signature verified by an independent impl" "$(sink_field accepted)" 0

chk "forged, unsigned" "$(post_webhook "${FORGED_BODY}")" "400"
chk "  refused as" "$(sink_rejected_by missing_signature)" "1"

now="$(date +%s)"
wrong="$(sign_body "whsec_the_attackers_own_secret" "${now}" "${FORGED_BODY}")"
chk "forged, signed with another secret" "$(post_webhook "${FORGED_BODY}" "${wrong}")" "400"
chk "  refused as" "$(sink_rejected_by bad_signature)" "1"

capture="$(capture_last_accepted)"
body="$(echo "${capture}" | python -c "import json,sys;print(json.load(sys.stdin)['body'])" 2>/dev/null)"
signature="$(echo "${capture}" | python -c "import json,sys;print(json.load(sys.stdin)['signature'])" 2>/dev/null)"
event_id="$(echo "${capture}" | python -c "import json,sys;print(json.load(sys.stdin)['eventId'])" 2>/dev/null)"

# One character of the amount, everything else identical. This is the attack the
# integrity half of the scheme exists for.
tampered="$(echo "${body}" | python -c "
import json,sys
d = json.load(sys.stdin)
d['data']['amountMinor'] = 50000000
print(json.dumps(d, separators=(',', ':')))
" 2>/dev/null)"
chk "tampered body, genuine signature" "$(post_webhook "${tampered}" "${signature}" "${event_id}")" "400"
chk "  refused as" "$(sink_rejected_by bad_signature)" "2"

# Rewriting t to now, keeping the captured v1. The freshness check would pass;
# the MAC covers t, so it does not.
stolen_v1="${signature##*,}"
chk "captured MAC with a fresh timestamp" \
    "$(post_webhook "${body}" "t=$(date +%s),${stolen_v1}" "${event_id}")" "400"

# THE FINDING.
chk "an unmodified replay, inside the window" \
    "$(post_webhook "${body}" "${signature}" "${event_id}")" "200"

echo
printf "   %-24s %s accepted, %s rejected %s\n" "sink totals" \
    "$(sink_field accepted)" "$(sink_field rejected)" \
    "$(sink_state | python -c "import json,sys;print(json.load(sys.stdin).get('rejectedBy'))" 2>/dev/null)"
echo

# ===================================================================== C =====

echo "=============================================================="
echo " ARM C - SIGNED, 2s TOLERANCE"
echo "=============================================================="
echo "   The same captured delivery, against a receiver with a two-second"
echo "   window. This is what timestamp replay protection actually does: it"
echo "   makes a capture perishable."
echo

restart_stack "${SECRET}" 2 false true

before="$(total_deliveries)"
payment="$(send_payment)"
echo "   real payment  ${payment:-NONE}"
wait_for_delivery "${before}" || echo "   (no delivery arrived)"
chk_gt "a fresh delivery still gets through" "$(sink_field accepted)" 0

capture="$(capture_last_accepted)"
fresh_body="$(echo "${capture}" | python -c "import json,sys;print(json.load(sys.stdin)['body'])" 2>/dev/null)"
fresh_sig="$(echo "${capture}" | python -c "import json,sys;print(json.load(sys.stdin)['signature'])" 2>/dev/null)"
fresh_id="$(echo "${capture}" | python -c "import json,sys;print(json.load(sys.stdin)['eventId'])" 2>/dev/null)"

echo "   holding the capture for 5 seconds"
sleep 5
chk "the same replay, 5s later" "$(post_webhook "${fresh_body}" "${fresh_sig}" "${fresh_id}")" "400"
chk "  refused as" "$(sink_rejected_by stale_timestamp)" "1"
echo

# ===================================================================== D =====

echo "=============================================================="
echo " ARM D - SIGNED, 300s TOLERANCE, RECEIVER DEDUPLICATES"
echo "=============================================================="
echo "   The replay arm B accepted, against a receiver that remembers event"
echo "   ids. Nothing about the signature changed - the fix is on the other"
echo "   side of the integration, which is the honest thing to tell a merchant."
echo

restart_stack "${SECRET}" 300 true true

before="$(total_deliveries)"
payment="$(send_payment)"
echo "   real payment  ${payment:-NONE}"
wait_for_delivery "${before}" || echo "   (no delivery arrived)"
chk_gt "the first delivery is accepted" "$(sink_field accepted)" 0

capture="$(capture_last_accepted)"
d_body="$(echo "${capture}" | python -c "import json,sys;print(json.load(sys.stdin)['body'])" 2>/dev/null)"
d_sig="$(echo "${capture}" | python -c "import json,sys;print(json.load(sys.stdin)['signature'])" 2>/dev/null)"
d_id="$(echo "${capture}" | python -c "import json,sys;print(json.load(sys.stdin)['eventId'])" 2>/dev/null)"

chk "the identical replay, now" "$(post_webhook "${d_body}" "${d_sig}" "${d_id}")" "400"
chk "  refused as" "$(sink_rejected_by duplicate_event)" "1"
echo

# ============================================================== the trace ====

if docker network inspect signoz-network >/dev/null 2>&1; then
    echo "=============================================================="
    echo " THE WEBHOOK IS IN THE PAYMENT'S TRACE"
    echo "=============================================================="
    echo "   Phase 6's remaining exit criterion: one trace, the merchant's"
    echo "   HTTP call and the webhook delivered seconds later."
    echo

    TRACE_ID="$(python -c "import os;print(os.urandom(16).hex())")"
    TP="00-${TRACE_ID}-$(python -c "import os;print(os.urandom(8).hex())")-01"
    before="$(total_deliveries)"
    payment="$(send_payment "${TP}")"
    echo "   traceparent   ${TP}"
    echo "   payment       ${payment:-NONE}"
    wait_for_delivery "${before}" || echo "   (no delivery arrived)"

    # What the RECEIVER saw. Stronger evidence than a span in ClickHouse: it is
    # the merchant's own view of the header, which is the thing that would be
    # missing if the RestClient had no observation registry on it.
    received_tp="$(sink_state | python -c "
import json,sys
d = json.load(sys.stdin)
for record in reversed(d.get('deliveries', [])):
    if record.get('accepted'):
        print(record.get('traceparent') or 'NONE')
        break
" 2>/dev/null)"
    echo "   the receiver saw traceparent: ${received_tp}"

    case "${received_tp}" in
        00-${TRACE_ID}-*) printf "   ok   %-46s %s\n" "the webhook carries the payment's trace" "same trace" ;;
        *) printf "   XX   %-46s %s\n" "the webhook carries the payment's trace" "${received_tp}"
           FAIL=$((FAIL+1)) ;;
    esac

    echo -n "   waiting for spans to settle"
    last=-1; stable=0
    for _ in $(seq 1 45); do
        now="$(docker exec signoz-telemetrystore-clickhouse-0-0 clickhouse-client -q \
            "SELECT count() FROM signoz_traces.distributed_signoz_index_v3 WHERE trace_id='${TRACE_ID}'" 2>/dev/null | tr -d '\r')"
        if [[ "${now}" == "${last}" && "${now}" != "0" ]]; then
            stable=$((stable+1)); [[ "${stable}" -ge 2 ]] && break
        else
            stable=0
        fi
        last="${now}"; echo -n "."; sleep 2
    done
    echo " ${last} spans"

    echo
    docker exec signoz-telemetrystore-clickhouse-0-0 clickhouse-client -q \
        "SELECT \`resource_string_service\$\$name\`, name, kind_string,
            toUnixTimestamp64Milli(timestamp) - (SELECT min(toUnixTimestamp64Milli(timestamp))
                FROM signoz_traces.distributed_signoz_index_v3 WHERE trace_id='${TRACE_ID}') AS t_ms
         FROM signoz_traces.distributed_signoz_index_v3
         WHERE trace_id='${TRACE_ID}' ORDER BY timestamp FORMAT TabSeparated" 2>/dev/null \
        | tr -d '\r' | while IFS=$'\t' read -r svc name kind t; do
            printf "     %-22s %-38s %-9s t+%sms\n" "${svc}" "${name}" "${kind}" "${t}"
        done
    echo
fi

# ------------------------------------------------------------------ PII -----

echo "=============================================================="
echo " PII IN WHAT THE MERCHANT RECEIVED"
echo "=============================================================="
echo "   The webhook body leaves our network entirely, which makes it the"
echo "   widest audience any payment data in this system has."

pans="$(sink_state | grep -oE '[0-9]{13,19}' | wc -l | tr -d ' ')"
chk "digit runs of card length in the bodies" "${pans}" "0"
secrets="$(sink_state | grep -ciE '"(cvv|expiry|pan|cardToken)"' | tr -d ' ')"
chk "cvv/expiry/pan/token fields" "${secrets}" "0"
echo

# ------------------------------------------------------------------ end -----

echo "=============================================================="
if [[ "${FAIL}" -eq 0 ]]; then
    echo " PASS - an independent verifier accepts our signatures, forgery and"
    echo "        tampering are refused, and a replay is refused only by the"
    echo "        two things that are not the signature: freshness and the"
    echo "        receiver's own memory."
else
    echo " FAIL - ${FAIL} check(s) failed."
fi
echo "=============================================================="
exit "${FAIL}"
