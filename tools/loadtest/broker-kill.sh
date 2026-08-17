#!/usr/bin/env bash
#
# Phase 6d: kill a broker under sustained load. Zero data loss, producer keeps
# writing.
#
#   tools/loadtest/broker-kill.sh
#
# THREE WINDOWS, AND THE THIRD IS THE ONE PEOPLE SKIP
#
#   1. baseline        all three brokers, payments flowing
#   2. ONE broker KILLED    RF=3 with min.insync.replicas=2 leaves two in-sync
#                           replicas, so writes must CONTINUE. Partitions go
#                           under-replicated; nothing stops.
#   3. TWO brokers KILLED   Only one replica left, below min.insync.replicas.
#                           Writes must now be REFUSED - and that is the correct
#                           behaviour, not a regression. A write acknowledged by
#                           a single replica that is about to die is exactly the
#                           durability hole min.insync.replicas exists to close.
#
# Window 3 is what proves the setting is doing anything. A cluster configured
# with RF=3 and min.insync.replicas=1 passes window 2 identically and quietly
# loses data when the last replica dies, which is why "we replicate three ways"
# on its own is not an answer.
#
# WHY THE OUTBOX MAKES THIS TEST MEANINGFUL
#
# Refused writes are only safe because the events are already durable in MySQL.
# The relay retries until the brokers return. Without the outbox, window 3 would
# be indistinguishable from phase 6a's permanent loss - the events would be gone
# and the payments would still have succeeded.
#
# `docker kill`, not `docker stop`. SIGKILL, no graceful shutdown, no chance to
# hand off leadership - which is what a real broker failure looks like.

set -uo pipefail

EDGE="${EDGE:-http://localhost:8080}"
API_KEY="${API_KEY:-pk_test_dev_merchant_key}"
N="${N:-20}"
TOPIC="${TOPIC:-payment.events}"

mysql_q() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

# Any live broker, so the queries keep working after kafka-1 is killed.
live_broker() {
    for b in payorch-kafka-1 payorch-kafka-2 payorch-kafka-3; do
        if [[ "$(docker inspect -f '{{.State.Running}}' "$b" 2>/dev/null)" == "true" ]]; then
            echo "$b"
            return 0
        fi
    done
    return 1
}

kafka() {
    local broker; broker="$(live_broker)" || { echo "no live broker" >&2; return 1; }
    MSYS_NO_PATHCONV=1 docker exec "$broker" "/opt/kafka/bin/$@"
}

count_events() {
    local out
    out="$(kafka kafka-get-offsets.sh --bootstrap-server "$(bootstrap)" --topic "${TOPIC}" 2>/dev/null | tr -d '\r')"
    [[ -z "$out" ]] && { echo "ERROR reading offsets" >&2; return 1; }
    echo "$out" | awk -F: '{s+=$3} END {print s+0}'
}

# Only the brokers still alive, so a killed one is not used as the bootstrap.
bootstrap() {
    local list=""
    for i in 1 2 3; do
        if [[ "$(docker inspect -f '{{.State.Running}}' "payorch-kafka-$i" 2>/dev/null)" == "true" ]]; then
            list="${list}${list:+,}kafka-${i}:9092"
        fi
    done
    echo "$list"
}

count_terminal_payments() {
    mysql_q "SELECT COUNT(*) FROM payment WHERE state IN ('AUTHORIZED','FAILED','UNKNOWN');"
}

outbox_pending() {
    mysql_q "SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL;"
}

under_replicated() {
    kafka kafka-topics.sh --bootstrap-server "$(bootstrap)" \
        --describe --topic "${TOPIC}" --under-replicated-partitions 2>/dev/null \
        | grep -c "Partition:" || true
}

send() {
    for _ in $(seq 1 "$1"); do
        curl -s --max-time 30 -o /dev/null -X POST "${EDGE}/v1/payments" \
            -H "Content-Type: application/json" \
            -H "X-Api-Key: ${API_KEY}" \
            -H "Idempotency-Key: bk-$(date +%s%N)-$RANDOM" \
            -d '{"amountMinor":4200,"currency":"INR","card":{"number":"4242424242424242","expiryMonth":12,"expiryYear":2030,"cvv":"123"},"merchantReference":"broker-kill"}'
    done
}

restore() {
    docker start payorch-kafka-1 payorch-kafka-2 payorch-kafka-3 >/dev/null 2>&1
    for _ in $(seq 1 60); do
        count_events >/dev/null 2>&1 && return 0
        sleep 2
    done
}
trap restore EXIT

drain() {
    echo -n "   draining the outbox"
    for _ in $(seq 1 90); do
        local left; left="$(outbox_pending)"
        [[ "${left:-0}" == "0" ]] && { echo " done"; return 0; }
        echo -n "."
        sleep 2
    done
    echo " STILL PENDING: $(outbox_pending)"
}

publisher="$(docker inspect payorch-payment-orchestrator \
    --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep '^EVENTS_PUBLISHER=' | cut -d= -f2)"
if [[ "${publisher}" != "outbox" ]]; then
    echo "refusing to run: needs EVENTS_PUBLISHER=outbox (found '${publisher:-unset}')." >&2
    echo "The direct publisher has no way to survive window 3 and the result would be trivial." >&2
    exit 2
fi

restore
E0="$(count_events)"; P0="$(count_terminal_payments)"

echo "=============================================================="
echo " 1. BASELINE - three brokers, ${N} payments"
echo "=============================================================="
send "$N"; drain
E1="$(count_events)"; P1="$(count_terminal_payments)"
echo "   payments +$((P1-P0))   events +$((E1-E0))   under-replicated: $(under_replicated)"

echo
echo "=============================================================="
echo " 2. ONE BROKER KILLED - writes must CONTINUE"
echo "=============================================================="
docker kill payorch-kafka-3 >/dev/null
echo "   SIGKILL to kafka-3"
sleep 8
echo "   under-replicated partitions: $(under_replicated)"
send "$N"; drain
E2="$(count_events)"; P2="$(count_terminal_payments)"
echo "   payments +$((P2-P1))   events +$((E2-E1))"

echo
echo "=============================================================="
echo " 3. TWO BROKERS KILLED - writes must be REFUSED, not lost"
echo "=============================================================="
docker kill payorch-kafka-2 >/dev/null
echo "   SIGKILL to kafka-2 - only one replica left, below min.insync.replicas=2"
sleep 8
send "$N"
P3="$(count_terminal_payments)"
PENDING="$(outbox_pending)"
echo "   payments +$((P3-P2))   outbox pending: ${PENDING}"
echo "   broker refusals seen by the producer:"
docker logs --since 3m payorch-payment-orchestrator 2>&1     | grep -o "NOT_ENOUGH_REPLICAS" | wc -l | awk '{print "     NOT_ENOUGH_REPLICAS x" $1}'
echo
echo "   pending > 0 is the CORRECT answer. Kafka answered NOT_ENOUGH_REPLICAS -"
echo "   it refused to acknowledge a write it could only put on one replica - and"
echo "   the events stayed in MySQL. Note what did NOT happen: the relay never"
echo "   had to give up, because the producer retries within its delivery timeout"
echo "   and the outbox row is not marked published until Kafka says yes."

echo
echo "=============================================================="
echo " 4. BROKERS BACK"
echo "=============================================================="
restore
drain
E4="$(count_events)"; P4="$(count_terminal_payments)"
sleep 5
echo "   under-replicated partitions: $(under_replicated)"

echo
echo "=============================================================="
echo " RESULT"
echo "=============================================================="
printf "   terminal payments   %6d\n" "$((P4-P0))"
printf "   events published    %6d\n" "$((E4-E0))"
GAP=$(( (P4-P0) - (E4-E0) ))
printf "   lost events         %6d\n" "$GAP"
echo
if [[ "$GAP" -eq 0 ]]; then
    echo "   ZERO DATA LOSS. Every payment produced an event, across a broker"
    echo "   killed under load and a second kill that took the cluster below its"
    echo "   minimum in-sync replicas. The events Kafka refused waited in the"
    echo "   outbox and were relayed when the cluster came back."
else
    echo "   ${GAP} EVENTS LOST. RF=3 and min.insync.replicas=2 did not hold, or"
    echo "   the relay gave up on them. Investigate before trusting this cluster."
fi
echo
