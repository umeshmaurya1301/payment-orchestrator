package com.payorch.ledger.domain;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.payorch.infra.logging.LogEvent;
import com.payorch.infra.logging.LogFields;
import com.payorch.ledger.consume.PaymentEventMessage;
import com.payorch.ledger.journal.JournalEntry;
import com.payorch.ledger.journal.JournalRepository;

/**
 * Posts one payment event to the ledger, exactly once.
 *
 * <h2>Which events move money</h2>
 *
 * <ul>
 *   <li><strong>AUTHORIZED</strong> posts two legs: the merchant is credited
 *       what they are owed, and the clearing account is debited the same amount.
 *       They sum to zero, always.</li>
 *   <li><strong>FAILED</strong> posts nothing. No money moved, so no entry
 *       moves. It is still journalled - see below.</li>
 *   <li><strong>UNKNOWN</strong> posts nothing, and this is the interesting
 *       one. The payment may have been charged and nobody knows; posting it
 *       would credit a merchant for money that may not exist, and posting
 *       nothing risks understating a real charge. The ledger records the
 *       uncertainty and waits for phase 8's reconciliation to resolve the
 *       payment into AUTHORIZED or FAILED, at which point the resolving event
 *       posts. <strong>A ledger that guesses is worse than a ledger that is
 *       behind.</strong></li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 *
 * <p>Delivery is at-least-once by design - the outbox relay publishes then
 * marks, and Kafka redelivers on rebalance - so this method WILL be called twice
 * for the same event. The defence is the {@code uq_entry_event_account}
 * constraint in the database, not a check in this code:
 *
 * <pre>
 *   if (entries.existsByEventId(id)) return;   // NOT sufficient
 * </pre>
 *
 * <p>That check and the insert are two statements, and two consumers handling a
 * redelivery concurrently both pass it. The unique constraint is the only thing
 * that makes the check and the write one atomic act. The pre-check below is kept
 * purely as a cheap fast path for the common case, and the constraint is what
 * actually guarantees correctness when it loses the race.
 */
@Service
public class LedgerPosting {

    private static final Logger log = LoggerFactory.getLogger(LedgerPosting.class);

    /** Every payment's counterparty. Seeded by V1 so it always exists. */
    public static final String CLEARING = "settlement:clearing";

    private final AccountRepository accounts;
    private final EntryRepository entries;
    private final JournalRepository journal;

    public LedgerPosting(AccountRepository accounts,
                         EntryRepository entries,
                         JournalRepository journal) {
        this.accounts = accounts;
        this.entries = entries;
        this.journal = journal;
    }

    /**
     * @return true if this call posted or journalled something, false if it was
     *         a duplicate that had already been handled
     */
    @Transactional
    public boolean post(PaymentEventMessage event) {
        // Fast path only. The constraint below is what is authoritative.
        if (entries.existsByEventId(event.eventId())) {
            return false;
        }

        boolean movesMoney = "AUTHORIZED".equals(event.state());
        String disposition = movesMoney ? "POSTED" : "IGNORED";
        String reason = switch (event.state()) {
            case "AUTHORIZED" -> null;
            case "FAILED" -> "no money moved";
            case "UNKNOWN" -> "outcome unknown - awaiting reconciliation, deliberately not posted";
            default -> "unrecognised state";
        };

        try {
            if (movesMoney) {
                LedgerAccount merchant = accountFor(
                        "merchant:" + event.merchantId(), event.currency());
                LedgerAccount clearing = accountFor(CLEARING, event.currency());

                // The two legs. Signs are opposite and magnitudes equal, so the
                // sum over the whole table stays zero - which is the invariant
                // the convergence test asserts.
                entries.save(LedgerEntry.of(event.eventId(), merchant.getId(),
                        event.paymentId(), event.amountMinor(), event.currency(),
                        "MERCHANT_CREDIT"));
                entries.save(LedgerEntry.of(event.eventId(), clearing.getId(),
                        event.paymentId(), -event.amountMinor(), event.currency(),
                        "CLEARING_DEBIT"));

                merchant.apply(event.amountMinor());
                clearing.apply(-event.amountMinor());
                // Flush now, inside the try, so a duplicate raises its
                // constraint violation HERE rather than at commit time where
                // this catch could not see it.
                entries.flush();
            }
        } catch (DataIntegrityViolationException duplicate) {
            // Lost the race with a concurrent redelivery. The other consumer
            // posted it; this one has nothing to do and must not treat that as a
            // failure, or the message would be retried forever.
            log.debug("duplicate event ignored by the unique constraint: {}", event.eventId());
            return false;
        }

        journalIfAbsent(event, disposition, reason);

        log.info("ledger posted",
                LogEvent.event()
                        .with(LogFields.PAYMENT_ID, event.paymentId().toString())
                        .with(LogFields.MERCHANT_ID, String.valueOf(event.merchantId()))
                        .with(LogFields.STATE, event.state())
                        .with(LogFields.AMOUNT_MINOR, event.amountMinor())
                        .with(LogFields.CURRENCY, event.currency())
                        .with(LogFields.OUTCOME, disposition)
                        .args());
        return true;
    }

    /**
     * The journal write is deliberately NOT part of the MySQL transaction.
     *
     * <p>It cannot be - they are different databases, and wrapping them would be
     * the distributed-transaction problem phase 6 spent its whole length
     * avoiding. So the ordering is chosen instead: balances first, journal
     * second. A crash between them leaves an entry with no journal line, which
     * an operator can reconstruct from the entries; the reverse would leave a
     * journal claiming a posting that never happened, which is a lie in the
     * audit trail.
     */
    private void journalIfAbsent(PaymentEventMessage event, String disposition, String reason) {
        if (journal.existsByEventId(event.eventId())) {
            return;
        }
        journal.save(JournalEntry.of(
                event.eventId(), event.paymentId(), event.merchantId(),
                event.type(), event.state(), event.amountMinor(), event.currency(),
                event.pspId(), disposition, reason, event.occurredAt()));
    }

    /**
     * Opens an account on first use.
     *
     * <p>A merchant this service has never heard of still has payments, and
     * dropping their events until somebody syncs a merchant table would lose
     * money silently. See {@code account_ref} in V1.
     */
    private LedgerAccount accountFor(String accountRef, String currency) {
        return accounts.findByAccountRefAndCurrency(accountRef, currency)
                .orElseGet(() -> accounts.save(LedgerAccount.open(accountRef, currency)));
    }

    /** Zero if the books balance. Used by the convergence check. */
    @Transactional(readOnly = true)
    public long imbalance() {
        return entries.sumOfAllEntries();
    }

    @Transactional(readOnly = true)
    public boolean hasEntryFor(UUID eventId) {
        return entries.existsByEventId(eventId);
    }
}
