package com.payorch.ledger.saga;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.payorch.infra.logging.LogEvent;
import com.payorch.infra.logging.LogFields;
import com.payorch.ledger.consume.PaymentEventMessage;

/**
 * Asks for a capture to be undone. Phase 6k.
 *
 * <h2>Where this sits</h2>
 *
 * <p>At the end of the retry ladder, which is the only place it makes sense. A
 * capture that failed once is not a saga problem - the 5s tier fixes most of
 * them and the 1m tier fixes most of the rest. Only after 5s, 1m and 10m have
 * all failed is it fair to say the ledger is not going to be able to account for
 * this capture, and only then is undoing real money at a real provider the
 * proportionate response.
 *
 * <p>So the compensation is the DLQ&#39;s alternative, not its replacement. The
 * record still lands in {@code payment.events.dlq} and is still replayable by a
 * human; what changes is that the money no longer sits in limbo until that human
 * arrives.
 *
 * <h2>Blocking send, deliberately</h2>
 *
 * <p>{@code send()} returns a future, and the tempting thing is to let it fly.
 * That would make a failed compensation request completely invisible: the DLT
 * handler returns, the record is marked handled, and the money stays captured
 * with nothing anywhere saying that the request to give it back was never
 * published. Waiting bounds the damage to a thread for a few seconds - the
 * producer&#39;s own {@code delivery.timeout.ms} and {@code max.block.ms} cap it -
 * and turns the failure into a counter somebody can alert on.
 *
 * <p>It still does not RETHROW. See {@link #request}.
 */
@Component
public class CompensationPublisher {

    private static final Logger log = LoggerFactory.getLogger(CompensationPublisher.class);

    private final KafkaTemplate<String, CompensationRequest> template;
    private final String topic;
    private final Duration timeout;

    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public CompensationPublisher(KafkaTemplate<String, CompensationRequest> compensationKafkaTemplate,
                                 @Value("${payorch.compensation.topic:payment.compensation}")
                                 String topic,
                                 @Value("${payorch.compensation.send-timeout-ms:10000}")
                                 long timeoutMs) {
        this.template = compensationKafkaTemplate;
        this.topic = topic;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /**
     * Publishes the request, and never throws.
     *
     * <p>The caller is the {@code @DltHandler}, whose entire purpose is to be
     * the place failures stop. A compensation that cannot be published is bad -
     * it is money left captured that should not be - but it is strictly less bad
     * than a DLT handler that throws, because {@code DltStrategy} decides what
     * happens next and phase 6f measured what that looks like when it goes
     * wrong: four messages became 10,306 records.
     *
     * <p>So the failure becomes {@link #failures}, which is published as
     * {@code payorch.ledger.compensation_failed} and asserted at zero in the
     * tests. The alert is the recovery path; there is not a better one, and
     * inventing one here would be a retry tier nobody configured.
     *
     * @return true if the broker acknowledged the request
     */
    public boolean request(PaymentEventMessage event, String reason) {
        CompensationRequest request = new CompensationRequest(
                event.paymentId(), event.eventId(), event.merchantId(),
                event.amountMinor(), event.currency(), reason, Instant.now());

        try {
            // Keyed by paymentId, like every other topic in this system. It
            // matters more here than usual: two compensations for one payment
            // must be ordered against each other, and a partition is the only
            // thing that orders anything in Kafka.
            template.send(topic, event.paymentId().toString(), request)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            requested.incrementAndGet();
            log.warn("compensation requested for a capture the ledger could not record",
                    LogEvent.event()
                            .with(LogFields.PAYMENT_ID, event.paymentId().toString())
                            .with(LogFields.STATE, event.state())
                            .with(LogFields.AMOUNT_MINOR, event.amountMinor())
                            .with(LogFields.COMPENSATION_REASON, reason)
                            .with(LogFields.OUTCOME, "REQUESTED")
                            .args());
            return true;
        } catch (InterruptedException e) {
            // Restore the flag rather than swallowing it. This runs on a
            // container thread that Spring interrupts during shutdown, and a
            // consumer that eats its own interrupt is one that will not stop.
            Thread.currentThread().interrupt();
            recordFailure(event, e);
            return false;
        } catch (Exception e) {
            recordFailure(event, e);
            return false;
        }
    }

    private void recordFailure(PaymentEventMessage event, Exception e) {
        failures.incrementAndGet();
        // Unstructured on purpose, like the DLT handler's own fallback: the
        // structured path is what may have broken, and using it to report its
        // own breakage is how a logging bug becomes silence.
        log.error("could not publish a compensation request - the capture on payment {} "
                + "stays captured and unaccounted for: {}", event.paymentId(), e.toString());
    }

    /** Compensation requests the broker acknowledged. */
    public long requested() {
        return requested.get();
    }

    /**
     * Requests that could not be published.
     *
     * <p>Must be zero. Every one of these is real money that was captured, that
     * the ledger cannot account for, and that nothing has been asked to give
     * back.
     */
    public long failures() {
        return failures.get();
    }
}
