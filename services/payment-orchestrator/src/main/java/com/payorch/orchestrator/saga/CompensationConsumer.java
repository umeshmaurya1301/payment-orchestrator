package com.payorch.orchestrator.saga;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.payorch.infra.logging.LogEvent;
import com.payorch.infra.logging.LogFields;
import com.payorch.orchestrator.PaymentService;

/**
 * The compensating half of the saga. Phase 6k.
 *
 * <h2>What arrives here</h2>
 *
 * <p>A capture that succeeded at a provider and that the ledger could not
 * account for after 5s, 1m and 10m of trying. Both services did their jobs; what
 * failed is the agreement between them, and neither of them can see that on its
 * own. This listener is the only place in the system where the disagreement is
 * visible, and undoing the capture is the only way to resolve it that does not
 * involve a person and a spreadsheet.
 *
 * <h2>Off by default</h2>
 *
 * <p>{@code payorch.saga.compensation.enabled}, defaulting to false, for the
 * same reason {@code payorch.events.publisher} defaults to {@code none}: phases
 * 1 to 6j run with no broker, and a service that refused to start without one
 * would make every earlier experiment depend on this one. It is turned on in the
 * {@code async} compose profile.
 *
 * <p>The consequence is worth stating rather than leaving implied: with this
 * off, the ledger still publishes compensation requests and nothing consumes
 * them. They accumulate on the topic. That is a deliberate choice over having
 * the ledger check whether anyone is listening - a producer that behaves
 * differently depending on the consumer&#39;s deployment is not decoupled - and the
 * requests are durable, so switching it on drains the backlog rather than losing
 * it.
 */
@Component
@ConditionalOnProperty(name = "payorch.saga.compensation.enabled", havingValue = "true")
public class CompensationConsumer {

    private static final Logger log = LoggerFactory.getLogger(CompensationConsumer.class);

    private final PaymentService payments;

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong reversed = new AtomicLong();
    private final AtomicLong noOps = new AtomicLong();

    public CompensationConsumer(PaymentService payments) {
        this.payments = payments;
    }

    /**
     * Reverses the capture, or decides not to.
     *
     * <h2>Nothing is caught here, on purpose</h2>
     *
     * <p>This is the opposite of the rule that governs the ledger&#39;s
     * {@code @DltHandler}, and the difference is which end of the ladder each
     * one is. That handler is where failures stop, so it must never throw. This
     * is the FIRST attempt at a compensation, so a failure must escape: the
     * error handler configured in {@link CompensationConfiguration} is what
     * turns it into three more tries and then a dead-letter topic a human reads.
     * Catching it here would silently drop the request to give real money back.
     *
     * <p>That includes an optimistic-lock conflict, and this is the one place in
     * the system where retrying one is the RIGHT answer. Phase 7d maps the same
     * exception to a 409 on the HTTP path, because the work there is not
     * repeatable - {@code capture} moves real money before it writes anything,
     * so an automatic retry would turn the lock working into a second provider
     * call. Here the work IS repeatable: re-reading and reversing again is
     * exactly correct, and the provider recognises a reversal it has already
     * performed. Same exception, opposite handling, decided by the work rather
     * than by the exception.
     *
     * <p>{@link PaymentService#reverseCapture} is idempotent, which is what
     * makes those retries safe. The at-least-once delivery that every other
     * consumer in this system designs for applies here too, and with more at
     * stake.
     */
    @KafkaListener(
            topics = "${payorch.saga.compensation.topic:payment.compensation}",
            groupId = "${payorch.saga.compensation.group:orchestrator-compensation}",
            containerFactory = "compensationListenerFactory")
    public void onCompensation(CompensationMessage message) {
        received.incrementAndGet();

        PaymentService.ReversalOutcome outcome =
                payments.reverseCapture(message.paymentId(), message.reason());

        if (outcome == PaymentService.ReversalOutcome.REVERSED) {
            reversed.incrementAndGet();
        } else {
            noOps.incrementAndGet();
        }

        log.info("compensation handled",
                LogEvent.event()
                        .with(LogFields.PAYMENT_ID, message.paymentId().toString())
                        .with(LogFields.MERCHANT_ID, String.valueOf(message.merchantId()))
                        .with(LogFields.AMOUNT_MINOR, message.amountMinor())
                        .with(LogFields.COMPENSATION_REASON, message.reason())
                        .with(LogFields.OUTCOME, outcome.name())
                        .args());
    }

    public long received() {
        return received.get();
    }

    /** Compensations that actually took money back from a provider. */
    public long reversed() {
        return reversed.get();
    }

    /**
     * Compensations that correctly did nothing.
     *
     * <p>A redelivery, or a request whose payment was no longer CAPTURED because
     * somebody replayed the DLQ first. Both are expected; see
     * {@link PaymentService.ReversalOutcome}.
     */
    public long noOps() {
        return noOps.get();
    }
}
