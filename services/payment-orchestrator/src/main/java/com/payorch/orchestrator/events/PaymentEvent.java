package com.payorch.orchestrator.events;

import java.time.Instant;
import java.util.UUID;

/**
 * What the rest of the system is told when a payment reaches a terminal state.
 *
 * <h2>Tokens only</h2>
 *
 * <p>The phase-6 rule is that Kafka messages carry <strong>tokens, never card
 * data</strong>, and the reason is not the happy path - it is the DLQ. A message
 * that fails repeatedly lands in a dead-letter topic where it sits for thirty
 * days and is eventually read by a human pasting it into a terminal. That is the
 * longest-lived, most-inspected copy of any message in the system, and it is
 * exactly the wrong place for a PAN.
 *
 * <p>So this record has no PAN field to forget to mask. {@code cardBin} and
 * {@code cardLast4} are the same six and four digits phase 1 already stores in
 * plain text - not card data on their own - and {@code cardToken} is a vault
 * reference that is worthless without the vault.
 *
 * <p><strong>Masking at produce time rather than at read time</strong> follows
 * from the same argument: a message that reaches the DLQ unmasked is already a
 * problem, whatever the reader does with it.
 *
 * @param eventId    unique per event, so a consumer can deduplicate. At-least-once
 *                   delivery is the design, not a defect to be engineered away.
 * @param paymentId  also the PARTITION KEY. Ordering in Kafka is per partition,
 *                   so keying by payment is what makes "events for one payment
 *                   arrive in order" true rather than merely likely.
 * @param occurredAt when the payment reached this state, not when the event was
 *                   published. The outbox makes those two different times, and
 *                   the difference is the relay lag.
 */
public record PaymentEvent(
        UUID eventId,
        UUID paymentId,
        UUID merchantId,
        String type,
        String state,
        long amountMinor,
        String currency,
        String pspId,
        String cardToken,
        String cardBin,
        String cardLast4,
        Instant occurredAt) {

    public static final String AUTHORIZED = "payment.authorized";
    public static final String CAPTURED = "payment.captured";
    /**
     * Phase 6k. The capture was given back.
     *
     * <p>The only event in this list that reports an <em>undo</em>, and the
     * ledger treats it as one: it posts the inverse of the authorization rather
     * than deleting anything. A ledger that could delete an entry would not be a
     * ledger.
     */
    public static final String REVERSED = "payment.reversed";

    public static final String FAILED = "payment.failed";
    public static final String UNKNOWN = "payment.unknown";

    /** The Kafka message key. See the {@code paymentId} note above. */
    public String partitionKey() {
        return paymentId.toString();
    }
}
