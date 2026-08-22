package com.payorch.orchestrator.events;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import org.infra.logging.LogEvent;
import org.infra.logging.LogFields;
import org.infra.observability.TraceCarrier;

/**
 * Moves outbox rows to Kafka, and marks them done.
 *
 * <p>The polling variant of the relay. Phase 6c builds the CDC variant on the
 * same table, so the two can be compared on latency and load rather than argued
 * about.
 *
 * <h2>Three phases, and the boundary between them is the correctness argument</h2>
 *
 * <pre>
 *   1. claim    short transaction: lock, stamp a lease, commit   NO NETWORK
 *   2. publish  no transaction at all                            NETWORK
 *   3. mark     short transaction per event                      NO NETWORK
 * </pre>
 *
 * <p>The first version fused all three into one {@code @Transactional} pass and
 * published while still holding the claim's locks. It measured four payments
 * stranded in {@code AUTHORIZING} with {@code Lock wait timeout exceeded}: the
 * claim scan takes next-key locks on the {@code published_at IS NULL} range,
 * which is precisely the gap every new outbox row is inserted into, so a payment
 * committing its terminal state waited fifty seconds on the relay and then
 * rolled back entirely. The relay had made the payment path worse than the event
 * loss it was built to fix.
 *
 * <p>That is the phase-2 rule reappearing - no transaction may be open across a
 * remote call - and this class's first javadoc rationalised it away on the
 * grounds that a background thread with its own connection is different. It is
 * not. A lock is a lock regardless of which thread holds it.
 *
 * <h2>What makes this safe</h2>
 *
 * <p><strong>Publish, then mark, in that order.</strong> The reverse - mark then
 * publish - loses events on a crash between the two, which is the failure the
 * outbox exists to remove. This order duplicates them instead, and a duplicate
 * is recoverable where a loss is not: the producer is idempotent, and consumers
 * deduplicate on {@code eventId}.
 *
 * <p><strong>At-least-once is the contract, not a defect.</strong> Trying to
 * make it exactly-once by hand produces a system that is neither.
 *
 * <p><strong>The claim query locks with {@code SKIP LOCKED}.</strong> Two
 * instances polling the same table would otherwise both publish every row. See
 * {@link OutboxRepository#claimUnpublished}.
 *
 * <p><strong>The transactions live in {@link OutboxStore}, not here.</strong>
 * Spring's {@code @Transactional} is proxy-based, so a method calling its own
 * annotated method bypasses it entirely - which would have left the marks
 * unflushed and republished every event on every poll. See that class.
 *
 * <h2>Phase 6g: the trace headers are injected by hand</h2>
 *
 * <p>Spring Kafka can inject trace context into a record automatically -
 * {@code KafkaTemplate.setObservationEnabled(true)} and it happens. That works
 * for the direct publisher, which sends on the request thread while the trace is
 * still current, and it is worth exactly nothing here: this method runs on a
 * scheduler thread, and the only context it could inject automatically is its
 * own polling loop's. Every event would arrive at the ledger correctly traced
 * into a trace that contains nothing but the relay.
 *
 * <p>So the context is read from the row and injected explicitly. See
 * {@link TraceCarrier}, {@code V11__outbox_traceparent.sql}, and note that the
 * span opened here is a CHILD of the stored context rather than a resumption of
 * it - the relay lag is then visible in the waterfall as the gap between the
 * request and the publish, which is the number phase 6c compared CDC against.
 *
 * <h2>What it costs, which phase 6c will measure</h2>
 *
 * <p>A query every poll interval whether or not there is work, and a publish
 * latency floor of up to one interval. That is the trade against CDC: this is
 * about fifty lines and needs no extra infrastructure, and it puts a constant
 * floor of queries on the primary.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** The span name for the publish. Named after the mechanism, not the topic. */
    public static final String PUBLISH_SPAN = "outbox publish";

    private final OutboxStore store;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final int batchSize;

    /**
     * Nullable. A relay in a service with no tracing publishes without headers,
     * which is what it did before phase 6g and is not a failure.
     */
    private final TraceCarrier traces;

    /**
     * How long a claim is honoured before another relay may take the row.
     *
     * <p>Longer than the producer's delivery timeout, so a merely slow relay
     * does not have its work stolen and published twice; short enough that a
     * relay which dies does not strand its batch for long.
     */
    private final Duration leaseDuration;

    private final AtomicLong relayed = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public OutboxRelay(OutboxStore store,
                       KafkaTemplate<String, String> kafka,
                       String topic,
                       int batchSize,
                       Duration leaseDuration,
                       TraceCarrier traces) {
        this.store = store;
        this.kafka = kafka;
        this.topic = topic;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.traces = traces;
    }

    /** Deliberately NOT {@code @Transactional}. See the class javadoc. */
    @Scheduled(fixedDelayString = "${payorch.events.outbox.poll-ms:500}")
    public void relay() {
        List<Claimed> batch = store.claim(leaseDuration, batchSize);
        if (batch.isEmpty()) {
            return;
        }

        for (Claimed event : batch) {
            try {
                // Outside any transaction. This is the network call the first
                // version made while holding the claim's locks.
                publish(event);
                store.markPublished(event.id());
                relayed.incrementAndGet();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                store.markFailed(event.id(), e.getMessage());
                failures.incrementAndGet();

                // Stop at the first failure. Continuing would publish later
                // events for other payments ahead of this one, and per-payment
                // ordering is the guarantee the partition key exists to provide.
                // The row stays unpublished; its lease expires and it is retried.
                log.warn("outbox relay failed - the event is still owed and will be retried",
                        LogEvent.event()
                                .with(LogFields.PAYMENT_ID, event.key())
                                .with(LogFields.OUTCOME, "RELAY_FAILED")
                                .with(LogFields.ERROR_CODE, e.getClass().getSimpleName())
                                .args());
                break;
            }
        }
    }

    /**
     * Publishes one event, carrying the trace context the row remembers.
     *
     * <p>The headers come from the span opened around the send, not from the
     * stored value directly, so what the consumer extracts identifies the
     * publish rather than the original request. Both are in the same trace; the
     * difference is whether the waterfall has a publish step in it.
     */
    private void publish(Claimed event) throws Exception {
        if (traces == null) {
            kafka.send(record(event, Map.of())).get();
            return;
        }
        traces.continuing(event.traceparent(), PUBLISH_SPAN,
                headers -> kafka.send(record(event, headers)).get());
    }

    /**
     * A record rather than the three-argument {@code send}, purely so headers
     * can be attached. Partition stays null: the key decides it, and that is
     * what makes per-payment ordering real.
     */
    private ProducerRecord<String, String> record(Claimed event, Map<String, String> headers) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, null, event.key(), event.payload());
        headers.forEach((name, value) ->
                record.headers().add(name, value.getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    /**
     * A claimed row, detached from JPA.
     *
     * @param traceparent the trace context captured when the row was written,
     *                    or null. See {@code V11__outbox_traceparent.sql}.
     */
    public record Claimed(UUID id, String key, String payload, String traceparent) {
    }

    public long relayed() {
        return relayed.get();
    }

    public long failures() {
        return failures.get();
    }

    /** Rows still owed. The number to alert on: a backlog that only grows is a broken relay. */
    public long pending() {
        return store.pending();
    }
}
