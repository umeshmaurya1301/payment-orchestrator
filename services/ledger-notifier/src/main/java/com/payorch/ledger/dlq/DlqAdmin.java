package com.payorch.ledger.dlq;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.kafka.support.KafkaHeaders;

import com.payorch.infra.observability.TraceCarrier;

/**
 * Reading and replaying the dead-letter queue.
 *
 * <h2>Why this is not a Mongo table of failures</h2>
 *
 * <p>The tempting design is to have the {@code @DltHandler} write each failure
 * into a collection and replay from there. It is easier to query and it is
 * wrong: it makes the DLQ topic decorative, and the record an operator replays
 * is then a reconstruction rather than the message. Every field the handler
 * forgot to copy is a field the replay silently drops.
 *
 * <p>So the topic is the record. This class reads it with an ordinary consumer
 * and republishes the <strong>original bytes</strong> to the main topic, exactly
 * as the outbox relay publishes the bytes the transaction committed rather than
 * a round trip through Jackson.
 *
 * <h2>Why replay commits offsets</h2>
 *
 * <p>Replay runs under its own consumer group, {@value #REPLAY_GROUP}, and
 * commits after publishing. Without that, a second replay resends everything
 * from the beginning - which idempotency would survive, but which makes the
 * count in the response a lie and makes "replay the DLQ" an operation nobody
 * can safely run twice during an incident.
 *
 * <p>The records stay in the topic either way; the offset is a bookmark, not a
 * delete. An operator who genuinely wants everything again resets the group.
 *
 * <h2>The failure mode this class must not have</h2>
 *
 * <p>Publishing is done with {@code send().get()} - synchronously, one at a
 * time, checking each result. An async fire-and-forget loop would report
 * "replayed 40" the instant it finished queueing them, and phase 6b already
 * measured what that costs: a producer that blocks or fails after the caller has
 * moved on turns a report into a guess.
 */
public class DlqAdmin {

    private static final Logger log = LoggerFactory.getLogger(DlqAdmin.class);

    /**
     * Distinct from the ledger's own group on purpose. Sharing it would make
     * every replay move the ledger's position in a topic it does not consume,
     * and a group is the unit of "who has read how far".
     */
    public static final String REPLAY_GROUP = "ledger-dlq-replay";

    private final String bootstrapServers;
    private final String dlqTopic;
    private final String mainTopic;
    private final KafkaTemplate<String, Object> kafka;
    private final AtomicLong replayedTotal = new AtomicLong();

    public DlqAdmin(String bootstrapServers, String dlqTopic, String mainTopic,
                    KafkaTemplate<String, Object> kafka) {
        this.bootstrapServers = bootstrapServers;
        this.dlqTopic = dlqTopic;
        this.mainTopic = mainTopic;
        this.kafka = kafka;
    }

    /**
     * Depth, and how much of it has been replayed.
     *
     * <p>Three numbers rather than one, because "DLQ depth" is ambiguous in a
     * log-structured queue. Records are not removed when handled, so the count
     * of records in the topic keeps rising forever and alerting on it would fire
     * permanently after the first incident. {@code pending} is the number worth
     * paging on.
     */
    public Map<String, Object> state() {
        try (KafkaConsumer<String, byte[]> consumer = consumer()) {
            List<TopicPartition> partitions = partitionsOf(consumer);
            Map<TopicPartition, Long> beginning = consumer.beginningOffsets(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            Map<TopicPartition, OffsetAndMetadata> committed =
                    consumer.committed(asSet(partitions));

            long total = 0;
            long pending = 0;
            for (TopicPartition tp : partitions) {
                long from = beginning.getOrDefault(tp, 0L);
                long to = end.getOrDefault(tp, 0L);
                total += to - from;
                OffsetAndMetadata mark = committed.get(tp);
                long position = mark == null ? from : Math.max(mark.offset(), from);
                pending += Math.max(0, to - position);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("topic", dlqTopic);
            out.put("partitions", partitions.size());
            out.put("records", total);
            out.put("pending", pending);
            out.put("replayed", total - pending);
            out.put("replayedThisProcess", replayedTotal.get());
            return out;
        }
    }

    /**
     * The forensics: what is in there, and why it failed.
     *
     * <p>Reads without committing, so looking does not consume. That distinction
     * has bitten enough people that it is worth being explicit: {@link #peek}
     * and {@link #replay} differ in exactly one thing, whether they commit, and
     * peeking during an incident must never be the reason a message is skipped.
     */
    public List<Map<String, Object>> peek(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = consumer()) {
            consumer.subscribe(List.of(dlqTopic));
            forEachPending(consumer, limit, false, record -> out.add(summarise(record)));
        }
        return out;
    }

    /**
     * Republishes up to {@code limit} pending records to the main topic.
     *
     * @return what happened, including the failure count - which must be
     *         reported rather than logged, because an operator running this
     *         during an incident needs to know whether to run it again
     */
    public Map<String, Object> replay(int limit) {
        AtomicLong sent = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        Map<String, Long> byOriginalTopic = new HashMap<>();
        Instant startedAt = Instant.now();

        try (KafkaConsumer<String, byte[]> consumer = consumer()) {
            consumer.subscribe(List.of(dlqTopic));
            forEachPending(consumer, limit, true, record -> {
                String original = header(record, KafkaHeaders.ORIGINAL_TOPIC);
                try {
                    // The original bytes, to the MAIN topic. Not to the tier the
                    // record failed on: a replay is a fresh attempt, and
                    // dropping it back into retry-600000 would give the operator
                    // a ten-minute wait for a fix they just deployed.
                    kafka.send(replayRecord(record)).get(10, TimeUnit.SECONDS);
                    sent.incrementAndGet();
                    byOriginalTopic.merge(original == null ? "unknown" : original, 1L, Long::sum);
                } catch (Exception e) {
                    failed.incrementAndGet();
                    log.warn("DLQ replay failed for offset {} of {}-{}: {}",
                            record.offset(), record.topic(), record.partition(), e.toString());
                    // Rethrown so the loop stops committing past a record that
                    // did not make it. Partial progress is committed; the rest
                    // stays pending, which is the state a second run can finish.
                    throw new IllegalStateException(e);
                }
            });
        } catch (IllegalStateException stopped) {
            log.warn("DLQ replay stopped early after {} record(s)", sent.get());
        }

        replayedTotal.addAndGet(sent.get());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("replayed", sent.get());
        out.put("failed", failed.get());
        out.put("target", mainTopic);
        out.put("byOriginalTopic", byOriginalTopic);
        out.put("tookMs", Duration.between(startedAt, Instant.now()).toMillis());
        log.warn("DLQ replay: {} record(s) republished to {}", sent.get(), mainTopic);
        return out;
    }

    /**
     * The record to republish: the original key and bytes, plus the original
     * trace context.
     *
     * <p>Phase 6g. Copying {@code traceparent} verbatim rather than starting a
     * fresh trace is the whole value of the header here. A replay happens
     * minutes or days after the payment, run by a human who has just fixed
     * something, and the question they are about to be asked is "what happened
     * to payment X". If the replay starts its own trace, the answer lives in two
     * traces joined by nothing, and the trace of the original request stops at
     * the DLQ with no ending.
     *
     * <p>Copied verbatim, so the replayed record's consume span is a SIBLING of
     * the four that failed - one trace showing the request, the publish, three
     * tiers of failure, the dead-letter, and the delivery that finally worked.
     *
     * <p>Only {@code traceparent} is copied. The rest of the DLQ headers are
     * forensics about the failure - the original topic, the exception, the
     * attempt count - and carrying them onto a fresh attempt would make the
     * replayed record claim to have already failed.
     */
    private ProducerRecord<String, Object> replayRecord(ConsumerRecord<String, byte[]> record) {
        ProducerRecord<String, Object> out =
                new ProducerRecord<>(mainTopic, null, record.key(), (Object) record.value());
        Header traceparent = record.headers().lastHeader(TraceCarrier.TRACEPARENT);
        if (traceparent != null) {
            out.headers().add(TraceCarrier.TRACEPARENT, traceparent.value());
        }
        return out;
    }

    // ---------------------------------------------------------------- guts --

    /**
     * Polls until the assignment is exhausted or {@code limit} is reached, and
     * commits after each successful record.
     *
     * <p>{@code commit} is what separates {@link #peek} from {@link #replay}.
     * Peeking must not move the group's position, or an operator looking at the
     * queue would be the reason a message never got replayed.
     *
     * <p>The empty-poll counter is not laziness. A subscribed consumer returns
     * nothing on its first polls while the group coordinator assigns partitions,
     * so "poll returned nothing" and "the topic is empty" are the same
     * observation early on. Three consecutive empties after any assignment
     * exists is the cheapest honest end condition.
     */
    private void forEachPending(KafkaConsumer<String, byte[]> consumer, int limit,
                                boolean commit,
                                Consumer<ConsumerRecord<String, byte[]>> handler) {
        int seen = 0;
        int emptyPolls = 0;
        while (seen < limit && emptyPolls < 3) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofSeconds(2));
            if (records.isEmpty()) {
                emptyPolls++;
                continue;
            }
            emptyPolls = 0;
            for (ConsumerRecord<String, byte[]> record : records) {
                if (seen >= limit) {
                    return;
                }
                handler.accept(record);
                seen++;
                if (commit) {
                    // After the handler, never before. Committing first would
                    // turn a failed replay into a lost message, which is the
                    // exact bug the DLQ exists to prevent one layer up.
                    consumer.commitSync(Map.of(
                            new TopicPartition(record.topic(), record.partition()),
                            new OffsetAndMetadata(record.offset() + 1)));
                }
            }
        }
    }

    private Map<String, Object> summarise(ConsumerRecord<String, byte[]> record) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("partition", record.partition());
        out.put("offset", record.offset());
        out.put("key", record.key());
        // The PLAIN header family, not the kafka_dlt-* one. Both exist on
        // KafkaHeaders and look interchangeable; the retry-topic machinery
        // writes these. Reading the other family returns null for everything
        // and produces a DLQ with no forensics that still reports depth
        // correctly - see PaymentEventConsumer.onDeadLetter.
        out.put("originalTopic", header(record, KafkaHeaders.ORIGINAL_TOPIC));
        // A raw big-endian int in four bytes, not text, and written once per
        // hop - so the LAST one is the total. Rendering it as a string puts
        // control characters in the response.
        out.put("attempts", attemptCount(
                record.headers().lastHeader(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS)));
        out.put("exception", header(record, KafkaHeaders.EXCEPTION_FQCN));
        out.put("exceptionMessage", header(record, KafkaHeaders.EXCEPTION_MESSAGE));
        out.put("failedAt", Instant.ofEpochMilli(record.timestamp()).toString());
        // The payload itself, capped. A DLQ inspection endpoint that returns
        // unbounded payloads is a way to page a whole topic into a browser, and
        // phase 6 already decided these messages carry tokens only - so the cap
        // is about response size, not about hiding anything.
        byte[] value = record.value();
        out.put("payload", value == null ? null
                : new String(value, StandardCharsets.UTF_8)
                        .substring(0, Math.min(value.length, 512)));
        return out;
    }

    private static int attemptCount(Header header) {
        if (header == null || header.value() == null || header.value().length < 4) {
            return -1;
        }
        return ByteBuffer.wrap(header.value()).getInt();
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private KafkaConsumer<String, byte[]> consumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, REPLAY_GROUP);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        // See ConsumerConfiguration: a subscription is enough to make the broker
        // create the topic, at its own defaults. An admin endpoint that silently
        // recreates the DLQ without replication while reporting depth 0 would be
        // the worst possible place for that to happen.
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Bytes, not JSON. A replay must reproduce the message, and deserializing
        // it here would also mean a poison message - the exact thing most likely
        // to be sitting in a DLQ - could not be replayed at all.
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return new KafkaConsumer<>(props);
    }

    private List<TopicPartition> partitionsOf(KafkaConsumer<String, byte[]> consumer) {
        List<PartitionInfo> info = consumer.partitionsFor(dlqTopic);
        List<TopicPartition> partitions = new ArrayList<>();
        if (info != null) {
            for (PartitionInfo p : info) {
                partitions.add(new TopicPartition(p.topic(), p.partition()));
            }
        }
        return partitions;
    }

    private static Set<TopicPartition> asSet(List<TopicPartition> partitions) {
        return new HashSet<>(partitions);
    }
}
