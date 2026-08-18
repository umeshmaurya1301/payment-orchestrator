package com.payorch.ledger.consume;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.payorch.infra.chaos.ChaosSeams;
import com.payorch.infra.logging.LogEvent;
import com.payorch.infra.logging.LogFields;
import com.payorch.ledger.domain.LedgerPosting;

/**
 * Consumes payment events into the ledger.
 *
 * <h2>Manual acknowledgement, and why it matters here</h2>
 *
 * <p>The container is configured to commit offsets only after the listener
 * returns normally. With auto-commit, an offset can be committed for a message
 * whose processing then throws - and the event is gone, permanently, in the one
 * service whose job is to not lose money. Committing after success turns that
 * into a redelivery, which the unique constraint in {@link LedgerPosting}
 * already handles.
 *
 * <p>That is the same trade the outbox made on the producer side: prefer a
 * duplicate you can detect over a loss you cannot.
 *
 * <h2>Phase 6f: the retry ladder</h2>
 *
 * <p>Until this phase the container used the default error handler, which
 * retries a record nine times in quick succession and then <strong>logs and
 * moves on</strong>. That is not a theoretical concern: phase 6e ended with 312
 * events silently skipped by exactly that path, discovered only because a
 * balance was short. A ledger whose default behaviour on a bad record is to
 * forget it is not a ledger.
 *
 * <p>{@code @RetryableTopic} replaces it with four topics and three waits:
 *
 * <pre>
 *   payment.events            first attempt
 *     -&gt; payment.events.retry-5000       5 seconds later
 *     -&gt; payment.events.retry-60000      1 minute later
 *     -&gt; payment.events.retry-600000    10 minutes later
 *     -&gt; payment.events.dlq             kept 30 days, read by a human
 * </pre>
 *
 * <h3>Non-blocking is the entire point</h3>
 *
 * <p>The obvious implementation - catch, sleep, try again - stalls the
 * partition. Ordering is per partition and the key is {@code paymentId}, so one
 * message that takes eleven minutes to give up would hold up every other payment
 * that hashed to the same partition for eleven minutes. Publishing the failure
 * FORWARD to a delay topic hands the wait to a different consumer and lets the
 * main partition advance immediately. The cost is that a retried event is now
 * out of order relative to its own siblings - acceptable here because
 * {@link LedgerPosting} is commutative over events, and it would not be for a
 * state machine that cared about arrival order.
 *
 * <h3>What is NOT retried</h3>
 *
 * <p>A {@link DeserializationException} goes straight to the DLQ. Retrying a
 * message that cannot be parsed spends eleven minutes on something no amount of
 * waiting will change, and it delays the moment a human sees the poison message.
 * The exclusion traverses causes because the {@code ErrorHandlingDeserializer}
 * failure arrives wrapped.
 */
@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    /**
     * The seam name. Armed by {@code tools/loadtest/retry-dlq.sh} at 30%.
     *
     * <p>It is reached BEFORE the ledger write, deliberately. Failing after the
     * write would test the unique constraint, which phase 6e already proved;
     * failing before it is what exercises the ladder, because the message has
     * been polled, has not been committed, and has done nothing yet.
     */
    public static final String SEAM = "ledger-consumer";

    private final LedgerPosting ledger;
    private final ChaosSeams seams;
    private final AtomicLong consumed = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong retried = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();
    private final AtomicLong dltLogFailures = new AtomicLong();

    public PaymentEventConsumer(LedgerPosting ledger, ChaosSeams seams) {
        this.ledger = ledger;
        this.seams = seams;
    }

    @RetryableTopic(
            attempts = "4",

            // 5s -> 60s -> 720s, capped by maxDelay to 600s. The cap is what
            // produces exactly the 5s/1m/10m ladder the phase specifies out of a
            // plain exponential policy, rather than a hand-written list.
            backOff = @BackOff(delay = 5_000, multiplier = 12, maxDelay = 600_000),

            // Name the topics after their DELAY, not their index.
            // "payment.events.retry-2" tells an operator nothing at 3am.
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_DELAY_VALUE,
            retryTopicSuffix = RetryTopics.RETRY_SUFFIX,
            dltTopicSuffix = RetryTopics.DLQ_SUFFIX,

            // The three delays differ, so each gets its own topic. Stated
            // explicitly because the alternative silently collapses tiers that
            // share a delay into one, and the ladder would then have fewer rungs
            // than the configuration appears to describe.
            sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.MULTIPLE_TOPICS,

            // THE IMPORTANT ONE. Left at its default of true, Spring creates any
            // missing retry topic using the BROKER defaults - RF=1, one
            // partition. Every message that failed once would then be sitting on
            // a single broker, in a cluster this project spent phases 6a and 6d
            // proving survives losing one. tools/kafka/topics.sh creates them
            // with RF=3 and min.insync.replicas=2 instead, and this makes a name
            // mismatch fail loudly rather than silently downgrade durability.
            autoCreateTopics = "false",

            // FAIL_ON_ERROR, and this is the single most important line in the
            // annotation. The DEFAULT is ALWAYS_RETRY_ON_ERROR, which means a
            // record whose DLT handling throws is republished to the DLT - the
            // same topic it is already on. Measured: four messages became
            // 10,306 records in three minutes, and the only thing that stopped
            // it was each republish appending stack-trace headers until the
            // record exceeded the producer's maximum request size.
            //
            // A dead-letter queue that re-queues its own failures is not a dead
            // end, and "retry on error" is a reasonable-sounding name for an
            // infinite loop.
            dltStrategy = DltStrategy.FAIL_ON_ERROR,

            exclude = DeserializationException.class,
            traversingCauses = "true",

            kafkaTemplate = "dltKafkaTemplate",
            listenerContainerFactory = "paymentEventListenerFactory",

            // One consumer per retry topic. These are low-volume by definition -
            // if they are not, the ladder is being used to paper over a
            // systematic failure and more threads would only get there faster.
            concurrency = "1")
    @KafkaListener(
            topics = "${payorch.ledger.topic:payment.events}",
            groupId = "${payorch.ledger.group:ledger-notifier}",
            containerFactory = "paymentEventListenerFactory")
    public void onPaymentEvent(PaymentEventMessage event,
                               @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false)
                               String topic) {

        // Armed only during an experiment. One hash lookup otherwise.
        seams.reach(SEAM);

        if (topic != null && !topic.equals(RetryTopics.MAIN)) {
            retried.incrementAndGet();
        }

        boolean applied = ledger.post(event);
        consumed.incrementAndGet();
        if (!applied) {
            long dupes = duplicates.incrementAndGet();
            // At-least-once working as designed, not an error. Logged at DEBUG
            // and counted, because the COUNT is interesting - a duplicate rate
            // that climbs means the relay or a rebalance is misbehaving - while
            // a line per duplicate would be noise.
            log.debug("duplicate event ignored ({} so far): {}", dupes, event.eventId());
            return;
        }

        log.debug("event consumed",
                LogEvent.event()
                        .with(LogFields.PAYMENT_ID, event.paymentId().toString())
                        .with(LogFields.STATE, event.state())
                        .args());
    }

    /**
     * The end of the ladder.
     *
     * <p>This method exists to <strong>log and count</strong>, not to recover.
     * Anything clever here - a fifth attempt, a fallback write - would be a
     * retry tier that nobody configured and that no operator can see. The
     * message stays in the DLQ topic until somebody replays it through
     * {@code /actuator/dlq}.
     *
     * <p>It logs at WARN with the source topic, the exception TYPE and the
     * attempt count. All three are also in headers on the dead-lettered record,
     * but headers are only visible to someone who already knows to go and read
     * the DLQ, and the point of this line is to be the thing that tells them.
     *
     * <p><strong>The header names are the plain ones, not the {@code DLT_*}
     * ones.</strong> {@code KafkaHeaders} defines both families and they look
     * interchangeable; the retry-topic machinery writes
     * {@code kafka_original-topic} and {@code kafka_exception-fqcn}, while the
     * {@code kafka_dlt-*} names are what a standalone
     * {@code DeadLetterPublishingRecoverer} can be configured to write. Reading
     * the wrong family does not fail - every header comes back null and the log
     * line says "unknown" for everything, which is a DLQ with no forensics that
     * still passes every assertion about depth. Measured: a full green run whose
     * entire forensic block printed {@code None}.
     *
     * <p>The exception <em>message</em> is deliberately absent. A
     * {@code DeserializationException} carries the bytes it could not parse, so
     * putting it in a structured field is a direct route from a malformed
     * message to a card number in a retained, indexed log store. It is available
     * through {@code /actuator/dlq}, where somebody is deliberately looking at
     * one record on the management port. See {@code LogFields.SOURCE_TOPIC}.
     *
     * <h3>Why the body is wrapped in a catch-everything</h3>
     *
     * <p>Because it is the last line, and on its first run it was not.
     *
     * <p>The original version passed ad-hoc field names to {@link LogEvent},
     * which enforces the {@link LogFields} allowlist and threw
     * {@code IllegalArgumentException}. That alone would have been a one-line
     * fix. What made it expensive is that {@code @RetryableTopic} defaults to
     * {@link org.springframework.kafka.retrytopic.DltStrategy#ALWAYS_RETRY_ON_ERROR},
     * so the failing record was republished <strong>to the DLQ it was already
     * on</strong> - four messages became 10,306 records in three minutes, and
     * the only thing that stopped it was each republish appending stack-trace
     * headers until the record exceeded the producer's maximum request size.
     *
     * <p>{@code dltStrategy = FAIL_ON_ERROR} closes that loop. This catch closes
     * the other half: a handler whose entire job is to be where failures stop
     * must not have a failure mode of its own.
     */
    @DltHandler
    public void onDeadLetter(PaymentEventMessage event,
                             @Header(name = KafkaHeaders.ORIGINAL_TOPIC, required = false)
                             byte[] originalTopic,
                             @Header(name = KafkaHeaders.EXCEPTION_FQCN, required = false)
                             String exceptionType,
                             @Header(name = RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS, required = false)
                             byte[] attempts) {

        deadLettered.incrementAndGet();
        try {
            log.warn("event dead-lettered after exhausting the retry ladder",
                    LogEvent.event()
                            .with(LogFields.PAYMENT_ID, String.valueOf(event.paymentId()))
                            .with(LogFields.STATE, event.state())
                            .with(LogFields.SOURCE_TOPIC, originalTopic == null
                                    ? "unknown" : new String(originalTopic, StandardCharsets.UTF_8))
                            .with(LogFields.FAILURE_TYPE, String.valueOf(exceptionType))
                            // The attempts header is a raw int in four bytes, not
                            // a string. Rendering it with new String() produces
                            // control characters in the log line.
                            .with(LogFields.RETRY_ATTEMPT, attemptCount(attempts))
                            .args());
        } catch (RuntimeException e) {
            // Never rethrow from here. Deliberately a plain unstructured line:
            // whatever broke is in the structured path, so using it again to
            // report the breakage is how a logging bug becomes silence.
            //
            // COUNTED, not just logged. A catch-all around the last line of
            // defence hides exactly the bug it is there to survive - this run
            // would have been green while the DLQ log line was broken. The
            // counter is asserted at zero in PaymentEventConsumerTest and
            // published as payorch.ledger.dlt_log_failed.
            dltLogFailures.incrementAndGet();
            log.error("event dead-lettered, and the DLT log line itself failed: {}", e.toString());
        }
    }

    private static int attemptCount(byte[] header) {
        if (header == null || header.length < 4) {
            return -1;
        }
        return ByteBuffer.wrap(header).getInt();
    }

    public long consumed() {
        return consumed.get();
    }

    public long duplicates() {
        return duplicates.get();
    }

    /** Deliveries that arrived on a retry topic rather than the main one. */
    public long retried() {
        return retried.get();
    }

    /** Records this instance saw reach the DLQ. */
    public long deadLettered() {
        return deadLettered.get();
    }

    /**
     * Times the DLT handler's own logging threw.
     *
     * <p>Must be zero. Anything else means the last line of defence is degraded
     * and the catch around it is the only reason the service is still running.
     */
    public long dltLogFailures() {
        return dltLogFailures.get();
    }
}
