package com.payorch.orchestrator.events;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.payorch.infra.persistence.Uuid7;
import com.payorch.orchestrator.domain.Payment;
import com.payorch.orchestrator.domain.PaymentState;

/**
 * Turns a terminal {@link Payment} into a {@link PaymentEvent} and hands it to
 * whichever publisher is configured.
 *
 * <p>A thin seam, and worth having rather than calling the publisher directly:
 * it is the one place that decides which state changes are worth telling anyone
 * about, and it keeps {@code PaymentService} free of the mapping.
 */
@Component
public class PaymentEvents {

    private final PaymentEventPublisher publisher;

    public PaymentEvents(PaymentEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Emits an event for a payment that has reached a terminal state.
     *
     * <p>Non-terminal states are ignored. {@code ROUTED} and {@code AUTHORIZING}
     * are this service's internal bookkeeping - a ledger has nothing to do with
     * the fact that a payment is currently in flight, and publishing them would
     * put this system's implementation detail into everybody else's contract.
     *
     * <p>{@code UNKNOWN} IS published, and that is the interesting one. A
     * downstream system needs to know that a payment exists whose outcome nobody
     * knows, because that is a payment somebody has to resolve - and phase 8's
     * reconciliation is the consumer that will.
     */
    public void emit(Payment payment) {
        String type = switch (payment.getState()) {
            case AUTHORIZED -> PaymentEvent.AUTHORIZED;
            case FAILED -> PaymentEvent.FAILED;
            case UNKNOWN -> PaymentEvent.UNKNOWN;
            default -> null;
        };
        if (type == null) {
            return;
        }

        publisher.publish(new PaymentEvent(
                Uuid7.generate(),
                payment.getId(),
                payment.getMerchantId(),
                type,
                payment.getState().name(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                payment.getPspId(),
                payment.getCardToken(),
                payment.getCardBin(),
                payment.getCardLast4(),
                Instant.now()));
    }
}
