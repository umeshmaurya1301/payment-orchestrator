#!/usr/bin/env bash
#
# Where the traffic went, second by second - the instrument phase 5 is measured
# with, and the source of the graph the README leads on.
#
#   tools/loadtest/capture-routing.sh <out.csv> [interval-seconds]
#
# WHY THE DATABASE AND NOT THE METRICS
#
# `payorch_provider_latency_samples{psp}` counts calls the connector MADE, which
# is a different question from where a payment was ROUTED. They diverge exactly
# when it matters most: a retry to the same provider is two calls and one
# routing decision, and a payment refused by an open breaker is a routing
# decision with no call at all. `payment_attempt` records the decision, one row
# per attempt, with the provider and the outcome it produced.
#
# It is also the authoritative record. A metric can be reset by a restart; the
# attempt rows are what a merchant would be shown and what phase 8's
# reconciliation will read.
#
# WHAT IT WRITES
#
#   ts,psp_id,outcome,attempts
#
# Long format, one row per (provider, outcome) per sample, because the set of
# providers is not known in advance and a wide format would need its columns
# rewritten every time psp_config changes. Deltas are computed at plot time from
# the cumulative counts, not here - if this script computed them, a missed
# sample would silently become a zero rather than a visible gap.

set -uo pipefail

OUT="${1:?usage: capture-routing.sh <out.csv> [interval-seconds]}"
INTERVAL="${2:-2}"

MYSQL_USER="${MYSQL_USER:-payorch}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-payorch}"

echo "ts,psp_id,outcome,attempts" > "$OUT"

# Stop cleanly on SIGTERM/SIGINT so the caller's `kill` leaves a complete file
# rather than a half-written last line.
running=true
trap 'running=false' TERM INT

while $running; do
    now=$(date +%s)
    # A single grouped query rather than one per provider: four round trips at a
    # 2s interval is enough load on the same connection pool the payment path
    # uses to perturb what is being measured. Phase 2 already found that
    # /actuator and the application share that pool.
    docker exec payorch-mysql mysql -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" -D payorch -N -B -e "
        SELECT psp_id, outcome, COUNT(*)
        FROM payment_attempt
        GROUP BY psp_id, outcome;" 2>/dev/null \
      | tr -d '\r' \
      | awk -v ts="$now" 'NF==3 {print ts","$1","$2","$3}' >> "$OUT"

    sleep "$INTERVAL"
done

echo "routing capture stopped: $(($(wc -l < "$OUT") - 1)) rows" >&2
