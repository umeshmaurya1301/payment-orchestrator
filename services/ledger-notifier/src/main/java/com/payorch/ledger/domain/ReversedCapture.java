package com.payorch.ledger.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A capture that was compensated, and must therefore never post.
 *
 * <p>Phase 6k. Written when a {@code REVERSED} event is posted; read when a
 * {@code CAPTURED} event arrives. In between sits the DLQ replay tool from phase
 * 6f, which is entirely capable of pushing the original capture back through
 * days later - see {@code V2__reversal.sql} for why nothing else in this service
 * would stop it.
 *
 * <p>Immutable, like {@link LedgerEntry} and for the same reason: it is a
 * statement about something that happened, not a flag whose current value
 * matters.
 */
@Entity
@Table(name = "reversed_capture")
public class ReversedCapture {

    /**
     * The payment. Not a surrogate id - a reversal is a fact about the payment,
     * so the payment IS the identity, and a redelivered reversal collides on the
     * primary key instead of writing a second tombstone.
     */
    @Id
    @Column(name = "payment_id", columnDefinition = "BINARY(16)")
    private UUID paymentId;

    @Column(name = "event_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID eventId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false)
    private String currency;

    protected ReversedCapture() {
    }

    public static ReversedCapture of(UUID paymentId, UUID eventId, long amountMinor,
                                     String currency) {
        ReversedCapture tombstone = new ReversedCapture();
        tombstone.paymentId = paymentId;
        tombstone.eventId = eventId;
        tombstone.amountMinor = amountMinor;
        tombstone.currency = currency;
        return tombstone;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getEventId() {
        return eventId;
    }
}
