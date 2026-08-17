package com.payorch.orchestrator.events;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import com.payorch.infra.logging.LogEvent;
import com.payorch.infra.logging.LogFields;

/**
 * Publishes straight to Kafka, immediately after the database commit.
 *
 * <p><strong>This is the wrong way to do it, built deliberately and first.</strong>
 * It is the "before" arm of phase 6: the atomicity problem has to be measured
 * before the outbox that fixes it is worth anything, in the same way phase 3
 * measured every failure before adding the component that prevented it.
 *
 * <h2>The gap</h2>
 *
 * <p>There are two writes here and no transaction spanning them:
 *
 * <pre>
 *   1. MySQL   payment -> AUTHORIZED    committed
 *   2. Kafka   payment.authorized       published
 * </pre>
 *
 * <p>Anything that happens between them is a permanent inconsistency, and there
 * are more ways in than people expect:
 *
 * <ul>
 *   <li>The broker is unreachable. The send fails, the payment is authorized,
 *       and <strong>nothing in this process remembers that an event was owed</strong>
 *       - the record of the debt was the in-memory call stack, and it is gone.</li>
 *   <li>The process dies after the commit. Same outcome, no error anywhere.</li>
 *   <li>The send succeeds and the commit is rolled back by something later in
 *       the transaction. Now a downstream ledger credits a payment that this
 *       system says never happened - a phantom event, which is worse than a lost
 *       one because it is invisible from here.</li>
 * </ul>
 *
 * <p>Note what does <em>not</em> fix it. Retrying the send helps only while the
 * process lives. Publishing before committing swaps lost events for phantom
 * ones. Wrapping both in a Kafka transaction gives atomicity between Kafka reads
 * and Kafka writes, and the MySQL write is not a Kafka write - the problem is
 * untouched. And 2PC across MySQL and Kafka is operationally awful and Kafka is
 * a poor XA participant.
 *
 * <p>The answer is to make the second write a consequence of the first rather
 * than a sibling of it, which is the transactional outbox.
 *
 * <h2>Counters</h2>
 *
 * <p>{@link #published} and {@link #lost} exist so the divergence is a number
 * rather than an anecdote. {@code lost} counts events this process knows it
 * failed to publish - which is a <em>lower bound</em> on the real loss, because
 * the crash case leaves nobody to increment anything.
 */
public class DirectKafkaPublisher implements PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DirectKafkaPublisher.class);

    private final KafkaTemplate<String, PaymentEvent> kafka;
    private final String topic;
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong lost = new AtomicLong();

    public DirectKafkaPublisher(KafkaTemplate<String, PaymentEvent> kafka, String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    @Override
    public void publish(PaymentEvent event) {
        try {
            // .get() rather than fire-and-forget, so a broker problem is visible
            // here at all. Asynchronous sending would make this arm look flawless
            // while losing exactly as many events - the failure would arrive on a
            // producer callback thread with no payment context and nowhere to go.
            kafka.send(topic, event.partitionKey(), event).get();
            published.incrementAndGet();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            long total = lost.incrementAndGet();

            // WARN and not ERROR, deliberately: nothing is broken from this
            // process's point of view. The payment succeeded and the caller was
            // told so. The only casualty is a downstream system that will never
            // hear about it, which is precisely why this failure mode survives
            // in real systems - it does not look like an outage.
            log.warn("payment event LOST - authorized in the database, never published",
                    LogEvent.event()
                            .with(LogFields.PAYMENT_ID, event.paymentId().toString())
                            .with(LogFields.STATE, event.state())
                            .with(LogFields.OUTCOME, "EVENT_LOST")
                            .with(LogFields.ERROR_CODE, e.getClass().getSimpleName())
                            .args());
            if (total == 1) {
                log.warn("this is the dual-write gap phase 6 exists to close", e);
            }
        }
    }

    public long published() {
        return published.get();
    }

    public long lost() {
        return lost.get();
    }
}
