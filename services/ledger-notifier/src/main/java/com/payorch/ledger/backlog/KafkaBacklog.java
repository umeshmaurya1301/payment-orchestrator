package com.payorch.ledger.backlog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How far behind a consumer group is, asked of the broker rather than of the
 * consumer.
 *
 * <h2>Why not the client-side metric</h2>
 *
 * <p>Kafka's own consumer publishes {@code records-lag-max}, and Micrometer will
 * expose it as {@code kafka.consumer.fetch.manager.records.lag.max} as soon as a
 * {@code MicrometerConsumerListener} is attached to the factory - which phase 6i
 * also does, because it is free and it is the number the consumer itself is
 * reacting to.
 *
 * <p>It is not sufficient, and the reason is the case that matters. The
 * client-side metric is computed by the client, from fetches the client made. A
 * consumer that has crashed, been descheduled, deadlocked on a merchant's
 * webhook endpoint or failed to rejoin its group after a rebalance publishes
 * <strong>no lag at all</strong> - the series simply stops. So the metric goes
 * quiet at precisely the moment the backlog starts growing without bound, and an
 * alert with a {@code > N} threshold on it cannot fire for the worst incident it
 * exists to catch. That is phase 4e's finding in a new costume: an alert that
 * looks configured and is unfireable.
 *
 * <p>This asks the broker instead: end offset minus committed offset, per
 * partition, summed. It is true whether or not the consumer is alive.
 *
 * <h2>What it still cannot survive</h2>
 *
 * <p>It runs <em>inside</em> the consuming service, so if the process dies, this
 * stops reporting too. That is a real limit and the honest fix is a separate
 * exporter (Kafka Exporter, Burrow) or the broker's own metrics - a lag monitor
 * that shares a fate with the thing it monitors is only half a monitor. What
 * makes the alert usable anyway is pairing the threshold with a
 * <strong>no-data</strong> condition, so silence is itself the alert. See
 * {@code docker/signoz/alerts/05-consumer-lag.json}.
 *
 * <h2>AdminClient, not a KafkaConsumer</h2>
 *
 * <p>A {@code KafkaConsumer} configured with the group id can read committed
 * offsets, and {@link com.payorch.ledger.dlq.DlqAdmin} does exactly that. Here
 * it would mean constructing a consumer in the ledger's own group every thirty
 * seconds; it would not join the group as long as nobody subscribes, and
 * "as long as nobody subscribes" is a property of this file that a future edit
 * can break, at which point a metrics poller starts triggering rebalances of the
 * consumer it is measuring. The AdminClient cannot do that at all.
 */
public class KafkaBacklog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaBacklog.class);

    private static final long TIMEOUT_SECONDS = 10;

    private final AdminClient admin;

    public KafkaBacklog(String bootstrapServers) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 8_000);
        this.admin = AdminClient.create(props);
    }

    /**
     * @param partitions how many the topic has
     * @param records    how many records the topic holds (end minus beginning)
     * @param lag        how many the group has not committed. THE number to alert
     *                   on: it returns to zero when somebody fixes the problem,
     *                   where {@code records} never falls in a log-structured topic
     */
    public record Backlog(int partitions, long records, long lag) {
        public static final Backlog UNKNOWN = new Backlog(0, -1, -1);
    }

    /**
     * Never throws. A broker that is unreachable is itself an incident, and one
     * being handled by every other part of this system - a metrics poller that
     * propagated it would take out the scheduler and stop publishing the other
     * gauges as well.
     *
     * @return {@link Backlog#UNKNOWN} on any failure, whose -1 values are
     *         deliberately not 0: a gauge reading zero says "no backlog", which
     *         is the most dangerous thing to say about a broker you cannot reach
     */
    public Backlog of(String topic, String group) {
        try {
            TopicDescription described = admin.describeTopics(List.of(topic))
                    .allTopicNames().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(topic);
            if (described == null) {
                return Backlog.UNKNOWN;
            }

            List<TopicPartition> partitions = described.partitions().stream()
                    .map(p -> new TopicPartition(topic, p.partition()))
                    .toList();

            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> earliest =
                    admin.listOffsets(spec(partitions, OffsetSpec.earliest()))
                            .all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latest =
                    admin.listOffsets(spec(partitions, OffsetSpec.latest()))
                            .all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetAndMetadata> committed =
                    admin.listConsumerGroupOffsets(group)
                            .partitionsToOffsetAndMetadata()
                            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            long records = 0;
            long lag = 0;
            for (TopicPartition tp : partitions) {
                long from = offset(earliest, tp);
                long to = offset(latest, tp);
                records += Math.max(0, to - from);

                OffsetAndMetadata mark = committed.get(tp);
                // A group that has never committed on this partition is behind
                // by everything still retained, not by zero. Falling back to the
                // EARLIEST offset rather than to `to` is what makes a brand new
                // or reset consumer group show as lagging, which is the truth.
                long position = mark == null ? from : Math.max(mark.offset(), from);
                lag += Math.max(0, to - position);
            }
            return new Backlog(partitions.size(), records, lag);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Backlog.UNKNOWN;
        } catch (Exception e) {
            log.warn("could not read the backlog for {}/{}: {}", topic, group, e.toString());
            return Backlog.UNKNOWN;
        }
    }

    private static Map<TopicPartition, OffsetSpec> spec(List<TopicPartition> partitions,
                                                        OffsetSpec which) {
        return partitions.stream().collect(Collectors.toMap(tp -> tp, tp -> which,
                (a, b) -> a, HashMap::new));
    }

    private static long offset(Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> map,
                               TopicPartition tp) {
        ListOffsetsResult.ListOffsetsResultInfo info = map.get(tp);
        return info == null ? 0 : info.offset();
    }

    @Override
    public void close() {
        admin.close();
    }
}
