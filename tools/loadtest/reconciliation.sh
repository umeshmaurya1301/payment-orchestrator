#!/usr/bin/env bash
#
# Phase 8: does reconciliation find all three mismatch classes?
#
#   tools/loadtest/reconciliation.sh
#
# WHY THE DEFECTS ARE PLANTED RATHER THAN HOPED FOR
#
# A recon run against whatever happens to be in the database produces a number,
# and a number with nothing to compare it to proves only that the job executes.
# The question is whether it finds each class, so this builds a settlement batch
# containing exactly one known defect of each kind and asserts the job reports
# precisely those.
#
#   MATCHED                 a real posted payment, correct amount        -> clean
#   SETTLED_NOT_IN_LEDGER   a payment id this ledger has never seen      -> found
#   AMOUNT_MISMATCH         a real payment, wrong amount in the file     -> found
#   LEDGER_NOT_SETTLED      every posted payment omitted from the file   -> found
#
# The last one needs no planting: a settlement batch of four lines against a
# journal of tens of thousands leaves almost everything unsettled, which is what
# that class looks like in reality on the day a file arrives late.
#
# THE ONE THAT MATTERS IS THE SECOND
#
# Money left a cardholder and nothing in this system knows. Experiment 15
# measured why no internal check can find it: both of this system's invariants
# stay green while the books are wrong about the world, because an invariant over
# our own tables cannot see a disagreement with a third party.

set -uo pipefail

LEDGER="${LEDGER:-http://localhost:8084}"
BATCH="${BATCH:-recon-$(date +%s)}"
FAIL=0

lq() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch_ledger -N -B -e "$1" 2>/dev/null | tr -d '\r'
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

jq_field() {
    python -c "
import json,sys
d = json.load(sys.stdin)
cur = d
for k in sys.argv[1].split('.'):
    cur = cur.get(k, {}) if isinstance(cur, dict) else {}
print(cur if not isinstance(cur, (dict, list)) else len(cur))
" "$1" 2>/dev/null
}

echo "=============================================================="
echo " PREFLIGHT"
echo "=============================================================="

if ! curl -s --max-time 5 "${LEDGER}/actuator/recon" >/dev/null 2>&1; then
    echo "   ${LEDGER}/actuator/recon is not answering" >&2
    exit 2
fi

# Two real payments the ledger has actually posted. Taken from the journal via
# MySQL's entry table so the amounts are what the ledger really recorded, not
# what this script assumes.
read -r MATCHED_ID MATCHED_AMT <<<"$(lq "
    SELECT LOWER(CONCAT(SUBSTR(HEX(payment_id),1,8),'-',SUBSTR(HEX(payment_id),9,4),'-',
                        SUBSTR(HEX(payment_id),13,4),'-',SUBSTR(HEX(payment_id),17,4),'-',
                        SUBSTR(HEX(payment_id),21))),
           ABS(amount_minor)
    FROM ledger_entry WHERE entry_type='MERCHANT_CREDIT' ORDER BY id DESC LIMIT 1;")"

read -r SKEWED_ID SKEWED_AMT <<<"$(lq "
    SELECT LOWER(CONCAT(SUBSTR(HEX(payment_id),1,8),'-',SUBSTR(HEX(payment_id),9,4),'-',
                        SUBSTR(HEX(payment_id),13,4),'-',SUBSTR(HEX(payment_id),17,4),'-',
                        SUBSTR(HEX(payment_id),21))),
           ABS(amount_minor)
    FROM ledger_entry WHERE entry_type='MERCHANT_CREDIT' ORDER BY id DESC LIMIT 1 OFFSET 1;")"

# A payment id this system has never issued. THE DOUBLE CHARGE.
GHOST_ID="00000000-0000-4000-8000-$(printf '%012d' "$((RANDOM * RANDOM % 999999999999))")"

printf "   %-24s %s (%s)\n" "matched payment" "${MATCHED_ID:0:18}..." "${MATCHED_AMT}"
printf "   %-24s %s (%s -> %s)\n" "amount-skewed" "${SKEWED_ID:0:18}..." "${SKEWED_AMT}" "$((SKEWED_AMT + 700))"
printf "   %-24s %s\n" "ghost (never issued)" "${GHOST_ID:0:18}..."
printf "   %-24s %s\n" "batch" "${BATCH}"

if [[ -z "${MATCHED_ID}" || -z "${SKEWED_ID}" ]]; then
    echo "   no posted ledger entries to reconcile against - run some payments first" >&2
    exit 2
fi
echo

echo "=============================================================="
echo " INGEST - four lines, three of them defective on purpose"
echo "=============================================================="
LINES="${MATCHED_ID}:${MATCHED_AMT},${SKEWED_ID}:$((SKEWED_AMT + 700)),${GHOST_ID}:9900"
INGESTED="$(curl -s -X POST "${LEDGER}/actuator/recon" \
    -H 'content-type: application/json' \
    -d "{\"batch\":\"${BATCH}\",\"lines\":\"${LINES}\"}" | jq_field ingested)"
printf "   %-24s %s\n" "lines ingested" "${INGESTED}"
chk "settlement lines accepted" "${INGESTED}" "3"
echo

echo "=============================================================="
echo " THE REPORT"
echo "=============================================================="
REPORT="$(curl -s "${LEDGER}/actuator/recon?batch=${BATCH}")"
echo "${REPORT}" | python -m json.tool 2>/dev/null | head -40

SNIL="$(echo "${REPORT}" | jq_field 'mismatches.SETTLED_NOT_IN_LEDGER.count')"
LNS="$(echo "${REPORT}" | jq_field 'mismatches.LEDGER_NOT_SETTLED.count')"
AMM="$(echo "${REPORT}" | jq_field 'mismatches.AMOUNT_MISMATCH.count')"
TOOK="$(echo "${REPORT}" | jq_field 'tookMs')"

echo
chk    "SETTLED_NOT_IN_LEDGER - the double charge" "${SNIL}" "1"
chk    "AMOUNT_MISMATCH" "${AMM}" "1"
chk_gt "LEDGER_NOT_SETTLED" "${LNS}" "0"
printf "   --   %-46s %sms\n" "aggregation took" "${TOOK}"

echo
echo "=============================================================="
if [[ "${FAIL}" -eq 0 ]]; then
    echo " PASS - all three classes found, and the matched line produced no"
    echo "        mismatch of any kind."
else
    echo " FAIL - ${FAIL} check(s) failed."
fi
echo "=============================================================="
exit "${FAIL}"
