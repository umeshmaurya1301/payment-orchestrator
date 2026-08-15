#!/usr/bin/env bash
#
# Samples every service's /actuator/prometheus during a run and writes one CSV.
#
#   tools/loadtest/capture-metrics.sh out.csv 2 &
#   k6 run tools/loadtest/ramp.js
#   kill %1
#
# Why this exists: k6 only ever sees the edge. Edge latency tells you something
# is wrong; it does not tell you what. The phase-2 trap list puts it plainly -
# the interesting number is usually one hop in, and it is almost always a pool.
#
# Full SigNoz is phase 4. Until then, scraping the endpoints on a timer and
# writing a flat file is enough to draw the graph, and it has the advantage of
# producing an artefact that can be committed alongside the writeup.

set -uo pipefail

OUT="${1:-metrics.csv}"
INTERVAL="${2:-2}"

# host:port pairs. mock-psp-simulator is included on purpose: an experiment that
# blames the system for the instrument's behaviour is worthless, so the
# simulator's own latency is recorded too.
SERVICES=(
    "payments-edge:8080"
    "payment-orchestrator:8081"
    "psp-connector:8083"
    "mock-psp-simulator:8085"
)

# The series worth having. Deliberately short: a capture that records everything
# is a capture nobody reads.
#
#   hikaricp_connections_*    the bounded resource. `pending` is THE number -
#                             threads parked waiting for a connection.
#   http_server_requests_*    server-side latency and count, per service.
#   jvm_threads_live          with virtual threads there is no fixed pool to
#                             saturate, so this shows where they actually pile up.
#   jvm_memory_used_bytes     heap, for the soak run.
#   process_cpu_usage         is it working, or waiting?
PATTERNS='^(hikaricp_connections|http_server_requests_seconds_count|http_server_requests_seconds_sum|http_server_requests_seconds_max|jvm_threads_live_threads|jvm_threads_started_threads_total|jvm_memory_used_bytes|jvm_gc_pause_seconds_sum|process_cpu_usage|executor_)'

echo "timestamp,service,metric,labels,value" > "$OUT"
echo "capturing to ${OUT} every ${INTERVAL}s - Ctrl-C or kill to stop" >&2

# Trap so a Ctrl-C leaves a valid file rather than a truncated line.
trap 'echo "capture stopped: $(wc -l < "$OUT") rows" >&2; exit 0' INT TERM

while true; do
    NOW=$(date +%s)
    for entry in "${SERVICES[@]}"; do
        name="${entry%%:*}"
        port="${entry##*:}"

        # --max-time so a hung service does not stall the whole capture. A gap
        # in the CSV is itself a finding; a frozen capture is just missing data.
        curl -s --max-time 2 "http://localhost:${port}/actuator/prometheus" 2>/dev/null \
            | grep -E "$PATTERNS" \
            | grep -v '^#' \
            | while read -r line; do
                metric="${line%%[ {]*}"
                value="${line##* }"
                labels=""
                if [[ "$line" == *"{"* ]]; then
                    labels="${line#*\{}"
                    labels="${labels%%\}*}"
                fi
                # Labels are quoted and commas inside them replaced, so the CSV
                # stays parseable without a real CSV writer.
                echo "${NOW},${name},${metric},\"${labels//,/;}\",${value}"
            done
    done >> "$OUT"
    sleep "$INTERVAL"
done
