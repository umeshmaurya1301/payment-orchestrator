package com.payorch.ledger.journal;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The immutable journal, in Mongo.
 *
 * <p>Append-only and never updated - which is why it is here rather than in
 * MySQL. It grows without bound, it is read by date range during an
 * investigation, and nothing ever needs it to be transactionally consistent with
 * a balance in the same statement. The balances are the relational half; this is
 * the history.
 *
 * <p>It also records what the ledger DECIDED, including for events that changed
 * no balance. A {@code payment.failed} moves no money and still belongs in the
 * journal, because "we saw this event and deliberately posted nothing" is
 * exactly what an auditor asks about when a payment is missing from the
 * balances.
 */
@Document(collection = "journal")
public class JournalEntry {

    @Id
    private String id;

    @Indexed(unique = true)
    private UUID eventId;

    @Indexed
    private UUID paymentId;

    private UUID merchantId;
    private String eventType;
    private String paymentState;
    private long amountMinor;
    private String currency;
    private String pspId;

    /** What the ledger did: POSTED, or IGNORED with a reason. */
    private String disposition;
    private String reason;

    @Indexed
    private Instant recordedAt;

    /** When the payment reached its terminal state, as opposed to when this row was written. */
    private Instant occurredAt;

    protected JournalEntry() {
    }

    public static JournalEntry of(UUID eventId, UUID paymentId, UUID merchantId,
                                  String eventType, String paymentState,
                                  long amountMinor, String currency, String pspId,
                                  String disposition, String reason, Instant occurredAt) {
        JournalEntry entry = new JournalEntry();
        entry.eventId = eventId;
        entry.paymentId = paymentId;
        entry.merchantId = merchantId;
        entry.eventType = eventType;
        entry.paymentState = paymentState;
        entry.amountMinor = amountMinor;
        entry.currency = currency;
        entry.pspId = pspId;
        entry.disposition = disposition;
        entry.reason = reason;
        entry.occurredAt = occurredAt;
        entry.recordedAt = Instant.now();
        return entry;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getDisposition() {
        return disposition;
    }
}
