package com.payorch.ledger.domain;

import java.util.UUID;

import com.payorch.infra.persistence.Uuid7;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One leg of a double-entry pair.
 *
 * <p>Immutable once written. There is no setter and no update path: correcting a
 * ledger is done by posting a reversing entry, never by editing history, which
 * is what makes the journal an audit trail rather than a cache of the current
 * opinion.
 */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    @Id
    @Column(name = "id", columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "event_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID eventId;

    @Column(name = "account_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID accountId;

    @Column(name = "payment_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID paymentId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "entry_type", nullable = false)
    private String entryType;

    protected LedgerEntry() {
    }

    public static LedgerEntry of(UUID eventId, UUID accountId, UUID paymentId,
                                 long amountMinor, String currency, String entryType) {
        LedgerEntry entry = new LedgerEntry();
        entry.id = Uuid7.generate();
        entry.eventId = eventId;
        entry.accountId = accountId;
        entry.paymentId = paymentId;
        entry.amountMinor = amountMinor;
        entry.currency = currency;
        entry.entryType = entryType;
        return entry;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public UUID getEventId() {
        return eventId;
    }
}
