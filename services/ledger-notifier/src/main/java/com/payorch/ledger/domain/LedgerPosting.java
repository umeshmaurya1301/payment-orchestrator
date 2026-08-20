package com.payorch.ledger.domain;

import java.util.List;
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
 *   <li><strong>CAPTURED</strong> posts the second pair: the clearing
 *       account is credited as the funds arrive and the card network is
 *       debited. See {@link #legsFor} for what the clearing balance means
 *       once both pairs exist.</li>
 *   <li><strong>REVERSED</strong> posts the inverse of the AUTHORIZATION -
 *       not of the capture - and writes a tombstone so a later DLQ replay of
 *       the capture cannot resurrect it. Phase 6k.</li>
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

    /**
     * Where captured funds come from. Phase 6j.
     *
     * <p>Opened on first use like a merchant account rather than seeded, because
     * a deployment that never captures anything should not carry a row implying
     * it does.
     */
    public static final String NETWORK = "settlement:card-network";

    private final AccountRepository accounts;
    private final EntryRepository entries;
    private final JournalRepository journal;

    /** Phase 6k. Captures that have been compensated and must never post. */
    private final ReversedCaptureRepository tombstones;

    public LedgerPosting(AccountRepository accounts,
                         EntryRepository entries,
                         JournalRepository journal,
                         ReversedCaptureRepository tombstones) {
        this.accounts = accounts;
        this.entries = entries;
        this.journal = journal;
        this.tombstones = tombstones;
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

        // Phase 6k. The one check no other idempotency in this service can
        // stand in for.
        //
        // This capture has never been posted - that is precisely why it was
        // dead-lettered and then compensated - so existsByEventId above said
        // false, and the unique constraint below would accept it happily. The
        // only thing that knows this event is stale is the tombstone written by
        // its own reversal.
        boolean compensated = "CAPTURED".equals(event.state())
                && tombstones.existsById(event.paymentId());

        List<Leg> legs = compensated ? List.of() : legsFor(event);
        String disposition = legs.isEmpty() ? "IGNORED" : "POSTED";
        String reason = compensated
                ? "capture was compensated before this arrived - see reversed_capture"
                : switch (event.state()) {
                    case "AUTHORIZED", "CAPTURED", "REVERSED" -> null;
                    case "FAILED" -> "no money moved";
                    case "UNKNOWN" -> "outcome unknown - awaiting reconciliation, deliberately not posted";
                    default -> "unrecognised state";
                };

        try {
            if (!legs.isEmpty()) {
                for (Leg leg : legs) {
                    LedgerAccount account = accountFor(leg.accountRef(), event.currency());
                    entries.save(LedgerEntry.of(event.eventId(), account.getId(),
                            event.paymentId(), leg.amountMinor(), event.currency(),
                            leg.entryType()));
                    // NOT account.apply(...). See AccountRepository.applyDelta -
                    // read-modify-write on a managed entity loses concurrent
                    // postings, and the clearing account is touched by every
                    // single payment, so it is the row every consumer thread
                    // races on. Measured drift before the fix: 1,911,000 minor
                    // units on one merchant account.
                    accounts.applyDelta(account.getId(), leg.amountMinor());
                }
                // Phase 6k. Same transaction as the legs it is about, on
                // purpose. A tombstone without its reversal entries would
                // suppress a capture that was never actually given back; the
                // entries without the tombstone would leave the replay hole
                // open. Neither half is any use alone, so neither half commits
                // alone.
                if ("REVERSED".equals(event.state())) {
                    tombstones.save(ReversedCapture.of(event.paymentId(), event.eventId(),
                            event.amountMinor(), event.currency()));
                }
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

    /** One side of a posting. Always produced in balanced pairs - see {@link #legsFor}. */
    private record Leg(String accountRef, long amountMinor, String entryType) {
    }

    /**
     * Which accounts an event moves, and by how much.
     *
     * <h2>Two events per payment, two different movements</h2>
     *
     * <p>Phase 6j gave a payment a second published state, and the pair is the
     * whole reason a ledger is more interesting than a counter:
     *
     * <pre>
     *   AUTHORIZED   merchant        +amount     the merchant is owed this
     *                clearing        -amount     and we carry the liability
     *
     *   CAPTURED     clearing        +amount     the funds arrive
     *                card-network    -amount     from the network
     *
     *   REVERSED     merchant        -amount     the merchant is owed nothing
     *                clearing        +amount     and we carry no liability
     * </pre>
     *
     * <p>Every pair sums to zero, so {@code SUM(amount_minor)} over the whole
     * table stays zero regardless of how many payments are half-finished. That
     * is the invariant the convergence check asserts and it is unchanged from
     * phase 6e.
     *
     * <p>What the second pair adds is a number nobody had before:
     * <strong>the clearing account nets to zero for a payment that has been
     * captured, and stays negative for one that has not.</strong> So the clearing
     * balance is exactly the outstanding authorized-but-uncaptured exposure -
     * money the merchant has been promised and that has not yet been collected
     * from anybody. Before this phase that number was unrepresentable, because
     * the ledger had no idea capture existed.
     *
     * <p>{@code UNKNOWN} still posts nothing, for the reason in the class
     * javadoc: a ledger that guesses is worse than a ledger that is behind.
     */
    private List<Leg> legsFor(PaymentEventMessage event) {
        String merchant = "merchant:" + event.merchantId();
        long amount = event.amountMinor();
        return switch (event.state()) {
            case "AUTHORIZED" -> List.of(
                    new Leg(merchant, amount, "MERCHANT_CREDIT"),
                    new Leg(CLEARING, -amount, "CLEARING_DEBIT"));
            case "CAPTURED" -> List.of(
                    new Leg(CLEARING, amount, "CLEARING_CREDIT"),
                    new Leg(NETWORK, -amount, "NETWORK_DEBIT"));
            // Phase 6k. THE INVERSE OF THE AUTHORIZATION, not of the capture.
            //
            // Worth being exact about, because the obvious reading is wrong. A
            // compensation is raised for a capture the ledger never posted, so
            // the clearing/card-network pair does not exist here and there is
            // nothing on it to undo. What DOES exist is the authorization: the
            // merchant is credited and we are carrying the liability. The
            // reversal cancels that, and all three accounts return to zero for
            // this payment - which is the correct answer, because money left the
            // cardholder and came back, and the books should end up saying
            // nothing happened.
            //
            // Reversing the capture legs instead would credit card-network for
            // funds it never sent and leave clearing short by the same amount.
            // SUM(amount_minor) would still be zero. That is the second time in
            // two phases the balanced-books invariant has been unable to see a
            // real error, and it is why drift() and this tombstone both exist.
            case "REVERSED" -> List.of(
                    new Leg(merchant, -amount, "MERCHANT_REVERSAL"),
                    new Leg(CLEARING, amount, "CLEARING_REVERSAL"));
            default -> List.of();
        };
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

    /**
     * Every account whose cached balance disagrees with its own entries.
     *
     * <p>Empty is the only healthy answer. This is the check that catches a lost
     * update, and it exists because the phase-6e convergence assertion cannot:
     * that one sums the ENTRIES, which stay correct while the cache beside them
     * drifts.
     */
    @Transactional(readOnly = true)
    public List<AccountRepository.Drift> drift() {
        return accounts.drift().stream().filter(d -> d.delta() != 0).toList();
    }

    /**
     * Rewrites every cached balance from the entries.
     *
     * <p>Safe to run at any time and safe to run twice, because the entries are
     * immutable and are the source of truth - which is the property that makes
     * a denormalized balance acceptable in the first place. If this could not be
     * done, the balance would not be a cache, it would be a second ledger.
     *
     * <p>Deliberately manual rather than scheduled. A repair that runs by itself
     * hides the bug that made it necessary: drift should be an alert, and this
     * should be what somebody runs after reading it.
     *
     * @return how many accounts were wrong
     */
    @Transactional
    public int repairBalances() {
        int repaired = 0;
        for (AccountRepository.Drift d : accounts.drift()) {
            if (d.delta() == 0) {
                continue;
            }
            accounts.applyDelta(d.id(), -d.delta());
            log.warn("repaired a drifted ledger balance",
                    LogEvent.event()
                            .with(LogFields.OUTCOME, "BALANCE_REPAIRED")
                            .with(LogFields.AMOUNT_MINOR, d.delta())
                            .args());
            repaired++;
        }
        return repaired;
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

    /** Whether this payment&#39;s capture has been compensated. Phase 6k. */
    @Transactional(readOnly = true)
    public boolean captureReversed(UUID paymentId) {
        return tombstones.existsById(paymentId);
    }

    /** How many captures this ledger has had to have compensated. */
    @Transactional(readOnly = true)
    public long reversedCaptures() {
        return tombstones.count();
    }
}
