package com.payorch.ledger.consume;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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
 */
@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final LedgerPosting ledger;
    private final AtomicLong consumed = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();

    public PaymentEventConsumer(LedgerPosting ledger) {
        this.ledger = ledger;
    }

    @KafkaListener(
            topics = "${payorch.ledger.topic:payment.events}",
            groupId = "${payorch.ledger.group:ledger-notifier}",
            containerFactory = "paymentEventListenerFactory")
    public void onPaymentEvent(PaymentEventMessage event) {
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

    public long consumed() {
        return consumed.get();
    }

    public long duplicates() {
        return duplicates.get();
    }
}
