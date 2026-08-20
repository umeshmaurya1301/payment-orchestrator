#!/usr/bin/env bash
#
# Phase 7b, on the live stack: 100 genuinely concurrent requests, one
# idempotency key, one payment.
#
#   tools/loadtest/idempotency-burst.sh          # 100 requests
#   N=250 tools/loadtest/idempotency-burst.sh    # past the merchant burst
#
# WHAT THE UNIT TESTS ALREADY COVER, AND WHAT THEY CANNOT
#
# 7b asserts this at the guard with 100 real virtual threads: 1 run, 100
# identical responses. At the HTTP boundary the same test drops to 16, because
# every waiter polls through its own REQUIRES_NEW transaction and a hundred of
# those against a test-sized H2 pool measures pool starvation rather than
# idempotency.
#
# So the number the criterion actually asks for has only ever been asserted
# against an in-process guard and a five-connection pool. This runs it against
# the real edge, the real Hikari pool, the real Redis-backed limiter and a real
# MySQL - which is the only configuration where "100 concurrent" means what the
# criterion means by it.
#
# WHY THE BURST SIZE IS 100 AND NOT 1000
#
# payments-edge is configured merchant-burst: 100. At 100 the requests fit
# inside the bucket and every one reaches the idempotency guard, which is the
# thing under test. Above it the limiter starts refusing, and a 429 is NOT an
# idempotency failure - it is a different subsystem doing its job earlier in the
# chain. The script counts them separately and says so rather than folding them
# into a pass or a fail.
#
# THE ASSERTION THAT MATTERS IS THE DATABASE, NOT THE RESPONSES
#
# A hundred identical response bodies would also be produced by a system that
# created a hundred payments and returned the first one's body. So the primary
# assertion is `SELECT COUNT(*)` on the payments actually created, and the
# response comparison is the corroborating check rather than the proof.

set -uo pipefail

EDGE="${EDGE:-http://localhost:8080}"
API_KEY="${API_KEY:-pk_test_dev_merchant_key}"
N="${N:-100}"

FAIL=0
OUT="$(mktemp -d)"
trap 'rm -rf "${OUT}"' EXIT

pq() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

chk() {
    if [[ "$2" == "$3" ]]; then
        printf "   ok   %-46s %s\n" "$1" "$2"
    else
        printf "   XX   %-46s %s (expected %s)\n" "$1" "$2" "$3"
        FAIL=$((FAIL+1))
    fi
}

KEY="burst-$(date +%s%N)-$RANDOM"
REF="idem-burst-$(date +%s%N)"

BODY="{\"amountMinor\":4200,\"currency\":\"INR\",\"card\":{\"number\":\"4242424242424242\",\"expiryMonth\":12,\"expiryYear\":2030,\"cvv\":\"123\"},\"merchantReference\":\"${REF}\"}"

echo "=============================================================="
echo " ${N} CONCURRENT REQUESTS, ONE IDEMPOTENCY KEY"
echo "=============================================================="
echo "   key            ${KEY}"
echo "   merchant burst 100 (requests above it are refused by the limiter,"
echo "                  which is a different subsystem and is counted apart)"
echo

# All N launched before any is waited on. `curl --parallel` would be tidier and
# is not equivalent: it ramps. The point of this test is that the requests
# ARRIVE together, so they are all backgrounded first and reaped afterwards.
for i in $(seq 1 "${N}"); do
    curl -s -o "${OUT}/body-${i}" -w '%{http_code}' \
        --max-time 60 \
        -X POST "${EDGE}/v1/payments" \
        -H "Content-Type: application/json" \
        -H "X-Api-Key: ${API_KEY}" \
        -H "Idempotency-Key: ${KEY}" \
        -d "${BODY}" > "${OUT}/code-${i}" 2>/dev/null &
done
wait

echo "   STATUS CODES"
# `curl -w '%{http_code}'` writes no trailing newline, so cat-ing 100 of these
# together produces one 300-character line rather than 100 codes to count.
for f in "${OUT}"/code-*; do cat "$f"; echo; done | sort | uniq -c | sort -rn | while read -r n code; do
    printf "     %-6s %s\n" "${code}" "${n}"
done

created="$(pq "SELECT COUNT(*) FROM payment WHERE merchant_reference = '${REF}';")"
records="$(pq "SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = '${KEY}';")"

# Distinct payment ids across every 2xx body. One is the correct answer; more
# than one means the guard let a second request through.
distinct_ids="$(for f in "${OUT}"/body-*; do
        python -c "
import json,sys
try:
    d=json.load(open(sys.argv[1], encoding='utf-8'))
    print(d.get('id',''))
except Exception:
    pass
" "$f" 2>/dev/null
    done | grep -v '^$' | sort -u | wc -l | tr -d ' ')"

ok2xx="$(grep -lE '^(200|201)$' "${OUT}"/code-* 2>/dev/null | wc -l | tr -d ' ')"
r429="$(grep -lE '^429$' "${OUT}"/code-* 2>/dev/null | wc -l | tr -d ' ')"
r5xx="$(grep -lE '^5[0-9][0-9]$' "${OUT}"/code-* 2>/dev/null | wc -l | tr -d ' ')"

echo
printf "   %-30s %s\n" "requests sent" "${N}"
printf "   %-30s %s\n" "2xx" "${ok2xx}"
printf "   %-30s %s\n" "429 (limiter, not idempotency)" "${r429}"
printf "   %-30s %s\n" "5xx" "${r5xx}"
echo

chk "payments actually created" "${created}" "1"
chk "idempotency records" "${records}" "1"
chk "distinct payment ids returned" "${distinct_ids}" "1"
chk "server errors" "${r5xx}" "0"

echo
echo "=============================================================="
if [[ "${FAIL}" -eq 0 ]]; then
    echo " PASS - ${N} requests arrived together, one payment exists, and every"
    echo "        answer described that same payment."
else
    echo " FAIL - ${FAIL} check(s) failed."
fi
echo "=============================================================="
exit "${FAIL}"
