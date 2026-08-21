#!/usr/bin/env bash
#
# Phase 8: what an index is worth, on this project's own data.
#
#   tools/loadtest/index-benchmark.sh
#
# WHY THE NUMBERS COME FROM EXPLAIN ANALYZE AND NOT FROM A STOPWATCH
#
# Every query here runs in single-digit milliseconds or less, and one
# `docker exec mysql` costs about fifty. Timing from the shell would measure
# process startup and report the index as worthless. EXPLAIN ANALYZE reports the
# server's own execution time and its own row counts, which is what the criterion
# asks for: rows examined before and after.
#
# WHY `IGNORE INDEX` FOR THE BEFORE ARM
#
# The first measurement of Q1 was taken with no index on the table at all -
# a table scan of 131,585 rows to return one. Once V12 ships that state cannot be
# reproduced without dropping an index the application depends on, so the before
# arm uses IGNORE INDEX, which produces exactly the plan the optimiser would have
# had. The two agree: the recorded no-index scan and the IGNORE INDEX scan
# examine the same 131,585 rows.
#
# THE TRAP THIS AVOIDS
#
# Phase 8's own trap list: "benchmarking on an empty table - every plan is a full
# scan and every scan is fast". This runs against 131,585 payments, 116,251
# attempts and 125,823 idempotency records accumulated by the experiments in this
# repository. Nothing here is generated for the benchmark.

set -uo pipefail

DB="${DB:-payorch}"
RUNS="${RUNS:-15}"
FAIL=0

q() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D "${DB}" -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

# The server's own elapsed time for the whole plan, in ms, from the root node's
# "actual time=start..end".
timed() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D "${DB}" -e "EXPLAIN ANALYZE $1" 2>/dev/null \
        | tr -d '\r' | grep -oE 'actual time=[0-9.]+\.\.[0-9.]+' | head -1 \
        | sed 's/.*\.\.//'
}

# Rows the plan actually touched at its deepest scan/lookup node.
rows_examined() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D "${DB}" -e "EXPLAIN ANALYZE $1" 2>/dev/null \
        | tr -d '\r' | grep -oE 'rows=[0-9]+ loops=1' | tail -1 | grep -oE '[0-9]+' | head -1
}

plan_line() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D "${DB}" -e "EXPLAIN $1" 2>/dev/null \
        | tr -d '\r' | tail -1
}

percentiles() {
    python -c "
import sys
xs = sorted(float(x) for x in sys.argv[1:] if x)
if not xs:
    print('n/a n/a'); raise SystemExit
def p(q):
    i = min(int(round((len(xs)-1)*q)), len(xs)-1)
    return xs[i]
print('%.3f %.3f' % (p(0.50), p(0.99)))
" "$@"
}

measure() {
    local label="$1" sql="$2"
    local times=()
    for _ in $(seq 1 "${RUNS}"); do
        times+=("$(timed "${sql}")")
    done
    local pct; pct="$(percentiles "${times[@]}")"
    local rows; rows="$(rows_examined "${sql}")"
    printf "   %-34s %10s %10s %12s\n" "${label}" \
        "$(echo "${pct}" | awk '{print $1}')" \
        "$(echo "${pct}" | awk '{print $2}')" \
        "${rows:-?}"
    LAST_P50="$(echo "${pct}" | awk '{print $1}')"
    LAST_ROWS="${rows:-0}"
}

REF="$(q "SELECT merchant_reference FROM payment WHERE merchant_reference IS NOT NULL ORDER BY id DESC LIMIT 1;")"
MERCHANT="$(q "SELECT LOWER(HEX(merchant_id)) FROM payment LIMIT 1;")"

echo "=============================================================="
echo " DATA UNDER TEST"
echo "=============================================================="
printf "   %-30s %s\n" "payment rows" "$(q 'SELECT COUNT(*) FROM payment;')"
printf "   %-30s %s\n" "distinct merchant_reference" "$(q 'SELECT COUNT(DISTINCT merchant_reference) FROM payment;')"
printf "   %-30s %s\n" "payment_attempt rows" "$(q 'SELECT COUNT(*) FROM payment_attempt;')"
echo
printf "   %-34s %10s %10s %12s\n" "" "p50 ms" "p99 ms" "rows exam."
printf "   %-34s %10s %10s %12s\n" "" "------" "------" "----------"

# ---------------------------------------------------------------- Q1 --------
# A support question: "what happened to my reference". High cardinality -
# 123,070 distinct values in 131,585 rows - so an index is close to a point
# lookup and a scan is the whole table.
Q1_SQL="SELECT id, state, amount_minor FROM payment WHERE merchant_reference='${REF}'"
echo "   Q1  point lookup by merchant_reference"
measure "    without the index" "SELECT id, state, amount_minor FROM payment IGNORE INDEX (ix_payment_merchant_reference) WHERE merchant_reference='${REF}'"
Q1_BEFORE_ROWS="${LAST_ROWS}"; Q1_BEFORE_P50="${LAST_P50}"
measure "    with ix_payment_merchant_reference" "${Q1_SQL}"
Q1_AFTER_ROWS="${LAST_ROWS}"; Q1_AFTER_P50="${LAST_P50}"
echo "        plan: $(plan_line "${Q1_SQL}" | awk '{print $(NF-1), $NF}')"
echo

# ---------------------------------------------------------------- Q2 --------
# A merchant dashboard: state and amount over a window. The COVERING index case.
#
# THE WINDOW IS COMPUTED, NOT HARD-CODED. This dataset has ONE merchant and every
# row falls inside the last seven days, so "merchant_id = X AND created_at >= now
# - 7d" selects the whole table and no index can help - the first version of this
# query measured a legitimate full scan twice and called it a covering index.
# Picking the busiest hour gives the range something to exclude.
#
# The check below must not simply grep for "Using index": the NON-covering plan
# reports "Using index condition", which contains it. That is the difference
# between reading the answer out of the index and using the index to decide which
# rows to go and fetch.
BUSY_HOUR="$(q "SELECT DATE_FORMAT(created_at,'%Y-%m-%d %H:00:00') FROM payment
                GROUP BY 1 ORDER BY COUNT(*) DESC LIMIT 1;")"
WINDOW="created_at >= '${BUSY_HOUR}' AND created_at < '${BUSY_HOUR}' + INTERVAL 1 HOUR"
Q2_SQL="SELECT state, amount_minor FROM payment FORCE INDEX (ix_payment_merchant_covering) WHERE merchant_id=UNHEX('${MERCHANT}') AND ${WINDOW}"
Q2_BEFORE="SELECT state, amount_minor FROM payment FORCE INDEX (ix_payment_merchant_created) WHERE merchant_id=UNHEX('${MERCHANT}') AND ${WINDOW}"

echo "   Q2  merchant dashboard, busiest hour (${BUSY_HOUR})"
printf "   %-34s %s\n" "    rows in window" "$(q "SELECT COUNT(*) FROM payment WHERE ${WINDOW};")"
measure "    ix_payment_merchant_created" "${Q2_BEFORE}"
measure "    ix_payment_merchant_covering" "${Q2_SQL}"

Q2_EXTRA_COV="$(plan_line "${Q2_SQL}" | grep -oE 'Using [a-z ]+(condition)?' | tr '\n' ';')"
Q2_EXTRA_OLD="$(plan_line "${Q2_BEFORE}" | grep -oE 'Using [a-z ]+(condition)?' | tr '\n' ';')"
echo "        covering     Extra: ${Q2_EXTRA_COV}"
echo "        non-covering Extra: ${Q2_EXTRA_OLD}"

# "Using index" NOT followed by "condition" - see the comment above.
if plan_line "${Q2_SQL}" | grep -qE 'Using index(;|$|[^ ])'    || plan_line "${Q2_SQL}" | grep -q 'Using where; Using index'; then
    printf "   ok   %-46s\n" "covering index confirmed: reads no rows"
else
    printf "   XX   %-46s\n" "EXPLAIN does not report a covering read"
    FAIL=$((FAIL+1))
fi
echo

# ---------------------------------------------------------------- Q3 --------
# THE DELIBERATELY BAD INDEX. currency is one value in this dataset, so the
# index has a single entry pointing at every row. It costs writes on every
# insert and buys nothing, and the optimiser knows it.
Q3_SQL="SELECT COUNT(*) FROM payment WHERE currency='INR'"
echo "   Q3  the bad index: WHERE currency='INR'"
printf "   %-34s %s\n" "    distinct currencies" "$(q 'SELECT COUNT(DISTINCT currency) FROM payment;')"
measure "    with ix_payment_currency" "${Q3_SQL}"
Q3_PLAN="$(plan_line "${Q3_SQL}")"
echo "        plan key: $(echo "${Q3_PLAN}" | awk '{print $6}')"
echo

echo "=============================================================="
echo " WHAT THE INDEXES BOUGHT"
echo "=============================================================="
printf "   %-40s %s -> %s\n" "Q1 rows examined" "${Q1_BEFORE_ROWS}" "${Q1_AFTER_ROWS}"
printf "   %-40s %s -> %s ms\n" "Q1 p50" "${Q1_BEFORE_P50}" "${Q1_AFTER_P50}"
echo

if [[ "${Q1_AFTER_ROWS:-999999}" -lt "${Q1_BEFORE_ROWS:-0}" ]]; then
    printf "   ok   %-46s %s\n" "the index removed the scan" "${Q1_AFTER_ROWS}"
else
    printf "   XX   %-46s %s\n" "no improvement in rows examined" "${Q1_AFTER_ROWS:-?}"
    FAIL=$((FAIL+1))
fi

echo "=============================================================="
[[ "${FAIL}" -eq 0 ]] && echo " PASS" || echo " FAIL - ${FAIL} check(s) failed."
echo "=============================================================="
exit "${FAIL}"
