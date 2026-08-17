#!/usr/bin/env bash
#
# The phase-6 topics, created explicitly and idempotently.
#
#   tools/kafka/topics.sh create   # create anything missing, then describe
#   tools/kafka/topics.sh describe # what exists now, and whether it is durable
#   tools/kafka/topics.sh delete   # tear them down (data loss, obviously)
#
# WHY A SCRIPT AND NOT AUTO-CREATION
#
# An auto-created topic takes the broker defaults, and the broker defaults are
# exactly what this phase is trying not to rely on: one partition, and whatever
# replication factor the cluster happens to think is reasonable. A topic created
# by accident on first produce is a topic nobody chose the durability of.
#
# It is also not a Spring `NewTopic` bean, for a reason worth stating: those run
# when the application starts, so the topics' durability would depend on which
# service booted first and whether it booted at all. Topics are infrastructure
# with a lifecycle longer than any one service.
#
# THE SETTINGS, AND WHY EACH ONE
#
#   --replication-factor 3      Survives losing a broker. The exit criterion is
#                               "kill one broker, zero data loss", and RF=2 would
#                               technically pass it while leaving no margin.
#
#   min.insync.replicas=2       The half that people forget. RF=3 alone means the
#                               data is COPIED to three brokers; it does not mean
#                               a write is REFUSED when they are unavailable.
#                               With acks=all and min.insync.replicas=2, a
#                               producer write fails rather than silently landing
#                               on one replica that is about to die. RF without
#                               min.insync.replicas is durability theatre.
#
#   --partitions 6              Ordering is per PARTITION, and the producer keys
#                               by paymentId, so all events for one payment land
#                               on one partition and arrive in order. Six because
#                               it is a small multiple of three brokers, so
#                               partitions distribute evenly, and because
#                               partitions can be added later but never removed.
#
# The retry topics implement the phase's 5s -> 1m -> 10m -> DLQ tiering. They are
# ordinary topics; what makes the retry non-blocking is the consumer publishing
# forward to the next tier rather than sleeping on the partition.

set -uo pipefail

BROKER="${BROKER:-kafka-1:9092}"
PARTITIONS="${PARTITIONS:-6}"
RF="${RF:-3}"
MIN_ISR="${MIN_ISR:-2}"

# Every topic this phase uses. Retention differs by role and the reasons are
# below - a DLQ that expires at the same rate as a live topic will drop the
# evidence somebody was about to investigate.
#
#   name                              retention   why
TOPICS=(
    "payment.events"                  # 7d   the event stream itself
    "payment.events.retry.5s"         # 7d   tier 1
    "payment.events.retry.1m"         # 7d   tier 2
    "payment.events.retry.10m"        # 7d   tier 3
    "payment.events.dlq"              # 30d  read by humans, days after the fact
)

retention_for() {
    case "$1" in
        *.dlq) echo 2592000000 ;;   # 30 days
        *)     echo 604800000 ;;    # 7 days
    esac
}

kafka() {
    MSYS_NO_PATHCONV=1 docker exec payorch-kafka-1 "/opt/kafka/bin/$@"
}

case "${1:-describe}" in

create)
    for topic in "${TOPICS[@]}"; do
        retention="$(retention_for "$topic")"
        echo "=== ${topic} ==="
        kafka kafka-topics.sh --bootstrap-server "${BROKER}" \
            --create --if-not-exists \
            --topic "${topic}" \
            --partitions "${PARTITIONS}" \
            --replication-factor "${RF}" \
            --config "min.insync.replicas=${MIN_ISR}" \
            --config "retention.ms=${retention}" 2>&1 | sed 's/^/  /'
    done
    echo
    "$0" describe
    ;;

describe)
    echo "=== topics ==="
    for topic in "${TOPICS[@]}"; do
        out="$(kafka kafka-topics.sh --bootstrap-server "${BROKER}" \
                   --describe --topic "${topic}" 2>/dev/null)"
        if [[ -z "$out" ]]; then
            echo "  ${topic}: MISSING - run '$0 create'"
            continue
        fi
        # PartitionCount, ReplicationFactor and the configs, then a durability
        # verdict rather than a wall of partition lines. The question an operator
        # actually has is "will this survive a broker", and that is RF and
        # min.insync.replicas together.
        echo "$out" | head -1 | sed 's/^/  /'
        isr_ok="$(echo "$out" | grep -c "min.insync.replicas=${MIN_ISR}")"
        under="$(echo "$out" | awk '/Partition:/ { split($0,a,"Isr: "); n=split(a[2],b,","); if (n < '"${MIN_ISR}"') c++ } END { print c+0 }')"
        if [[ "$isr_ok" -ge 1 && "$under" -eq 0 ]]; then
            echo "      durable: RF=${RF}, min.insync.replicas=${MIN_ISR}, no under-replicated partitions"
        else
            echo "      NOT DURABLE: min.insync.replicas set=${isr_ok}, under-replicated partitions=${under}"
        fi
    done
    ;;

delete)
    for topic in "${TOPICS[@]}"; do
        kafka kafka-topics.sh --bootstrap-server "${BROKER}" \
            --delete --topic "${topic}" 2>&1 | sed 's/^/  /'
    done
    ;;

*)
    echo "usage: $0 {create|describe|delete}" >&2
    exit 2
    ;;
esac
