#!/usr/bin/env bash
#
# Phase 6c: polling relay vs Debezium CDC, on the same traffic, at the same time.
#
#   tools/loadtest/relay-comparison.sh
#
# The phase-6 plan asks for both variants so the trade is a measurement rather
# than an opinion. Both read the SAME outbox table and publish to different
# topics, so this is one population of payments seen two ways - not two runs at
# two different times with two different amounts of load.
#
# WHAT IS MEASURED
#
#   1. PUBLISH LAG. The event carries `occurredAt`, stamped when the payment
#      reached its terminal state. Kafka stamps each message with a CreateTime.
#      The difference is how long the event took to get out, end to end, and it
#      is the number the two designs actually differ on.
#
#   2. IDLE DATABASE LOAD. The polling relay issues a claim query every interval
#      whether or not there is work; CDC issues none, because it reads the binlog
#      the database was writing anyway. Measured as Com_select over a quiet
#      window - the cost nobody notices until the table is large and the cluster
#      is busy.
#
# WHAT IS NOT MEASURED, AND SHOULD BE SAID OUT LOUD
#
# Operational cost. Whatever the numbers below say, CDC added a container, a
# connector config, a schema-history topic, a MySQL account holding GLOBAL
# replication privileges, and a second component that can report RUNNING while
# silently publishing nothing - which it did, on first configuration. None of
# that is a number this script can produce, and it is why the polling relay is
# the default.

set -uo pipefail

EDGE="${EDGE:-http://localhost:8080}"
API_KEY="${API_KEY:-pk_test_dev_merchant_key}"
N="${N:-30}"
IDLE_SECONDS="${IDLE_SECONDS:-20}"
SAMPLE="${SAMPLE:-20}"

mysql_q() {
    docker exec payorch-mysql mysql -uroot -proot -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

send() {
    for _ in $(seq 1 "$1"); do
        curl -s --max-time 30 -o /dev/null -X POST "${EDGE}/v1/payments" \
            -H "Content-Type: application/json" \
            -H "X-Api-Key: ${API_KEY}" \
            -H "Idempotency-Key: cmp-$(date +%s%N)-$RANDOM" \
            -d '{"amountMinor":4200,"currency":"INR","card":{"number":"4242424242424242","expiryMonth":12,"expiryYear":2030,"cvv":"123"},"merchantReference":"relay-compare"}'
    done
}

# (Kafka CreateTime - event occurredAt) for the newest SAMPLE messages.
#
# Reads EVERY partition from the beginning and trims in Python, rather than
# `--offset -N --partition 0`. Events are keyed by paymentId across six
# partitions, so one partition holds roughly a sixth of them - the first version
# of this read none at all and reported "no messages" against two topics that
# were full.
lag_for() {
    local topic=$1
    MSYS_NO_PATHCONV=1 docker exec payorch-kafka-1 \
        /opt/kafka/bin/kafka-console-consumer.sh \
        --bootstrap-server kafka-1:9092 \
        --topic "$topic" \
        --property print.timestamp=true \
        --from-beginning --timeout-ms 12000 2>/dev/null \
      | WANT="$SAMPLE" python -c '
import sys, json, os, datetime, statistics

want = int(os.environ.get("WANT", "20"))
lags = []
for line in sys.stdin:
    line = line.strip()
    if not line.startswith("CreateTime:"):
        continue
    try:
        stamp, payload = line.split("\t", 1)
        kafka_ms = int(stamp.split(":", 1)[1])
        occurred = json.loads(payload)["occurredAt"].replace("Z", "+00:00")
        # Instant.toString() carries nanoseconds; fromisoformat wants microseconds.
        head, dot, tail = occurred.partition(".")
        if dot:
            frac, plus, tz = tail.partition("+")
            occurred = head + "." + frac[:6] + "+" + tz
        ev_ms = datetime.datetime.fromisoformat(occurred).timestamp() * 1000
        lags.append(kafka_ms - ev_ms)
    except Exception:
        continue

lags = lags[-want:]
if not lags:
    print("    no messages read")
else:
    ordered = sorted(lags)
    p95 = ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))]
    print("    n=%d  p50=%.0fms  p95=%.0fms  max=%.0fms"
          % (len(ordered), statistics.median(ordered), p95, ordered[-1]))
'
}

echo "=============================================================="
echo " 1. PUBLISH LAG - ${N} payments, both arms, one population"
echo "=============================================================="
send "$N"
sleep 8

echo "  polling relay (payment.events)"
lag_for payment.events
echo "  debezium CDC  (payment.events.cdc)"
lag_for payment.events.cdc

echo
echo "=============================================================="
echo " 2. IDLE DATABASE LOAD - ${IDLE_SECONDS}s with no payments"
echo "=============================================================="
S0="$(mysql_q "SHOW GLOBAL STATUS LIKE 'Com_select';" | awk '{print $2}')"
sleep "$IDLE_SECONDS"
S1="$(mysql_q "SHOW GLOBAL STATUS LIKE 'Com_select';" | awk '{print $2}')"
QUERIES=$(( S1 - S0 ))
echo "   SELECTs during ${IDLE_SECONDS}s idle: ${QUERIES}  (~$((QUERIES / IDLE_SECONDS))/s)"
echo
echo "   The polling relay's claim query runs every interval whether or not there"
echo "   is anything to send. CDC contributes none of these - it reads the binlog"
echo "   the database was already writing."
echo
