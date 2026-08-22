package com.payorch.ledger.domain;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.infra.chaos.ChaosSeams;
import org.infra.logging.LogEvent;
import org.infra.logging.LogFields;

/**
 * Moves funds between two ledger accounts, holding both. Phase 7e.
 *
 * <h2>Why this needs locks when a posting does not</h2>
 *
 * <p>{@link AccountRepository#applyDelta} handles a posting with no
 * application-level lock at all - {@code balance = balance + :delta} is one
 * statement, and the row lock InnoDB holds for its duration is the critical
 * section. Nothing can interleave, because there is no gap to interleave in.
 *
 * <p>A transfer is a different shape. "Move this amount only if the source has
 * it" requires reading the balance, deciding in Java, and then writing - three
 * steps with two gaps, and a concurrent transfer landing in either gap makes the
 * decision wrong. No atomic delta expresses a condition. This is the case
 * pessimistic locking is actually for, and it is why the phase asks for it on
 * balances even though the ledger's hot path stopped needing it in 6j.
 *
 * <h2>And why holding two of them is dangerous</h2>
 *
 * <p>A transfer holds <strong>two</strong> row locks at once, and that is the
 * entire ingredient list for a deadlock: two transactions, two resources,
 * acquired in different orders. {@code transfer(A, B)} takes A then B;
 * {@code transfer(B, A)} takes B then A. Run them at the same moment and each
 * holds what the other is waiting for, forever - until InnoDB notices and kills
 * one of them.
 *
 * <p>This is not exotic. Two merchants settling against each other, a refund
 * crossing a payout, any pair of accounts that can appear on both sides of a
 * movement. It is rare enough to survive testing and common enough to happen in
 * production, which is the worst combination a bug can have.
 *
 * <h2>The fix, and why the ordering is on the ID</h2>
 *
 * <p>Sort the accounts before locking. If every transaction takes its locks in
 * the same order, no cycle can form - the transaction holding the
 * lowest-numbered lock is always able to proceed, so somebody always makes
 * progress. This is the standard fix and it is the only one that scales:
 * retry-on-deadlock treats the symptom, works, and gets slower exactly as
 * contention rises, which is when it is needed most.
 *
 * <p>The order is on {@code id} rather than on {@code accountRef} because the id
 * is the primary key, is immutable, and is what the row lock is actually taken
 * against. Ordering on a mutable or derived value is an ordering that can
 * disagree with itself across two transactions holding different snapshots.
 *
 * <h2>The broken ordering is still reachable, deliberately</h2>
 *
 * <p>{@code payorch.ledger.transfer.lock-ordering=declared} keeps the
 * deadlocking version selectable, the same way {@code EVENTS_PUBLISHER=direct}
 * keeps phase 6's naive dual-write runnable. A "before" that can only be quoted
 * from a write-up is a "before" nobody can re-check, and the phase asks for both
 * states to be documented - which means both states have to exist.
 */
@Service
public class LedgerTransfer {

    private static final Logger log = LoggerFactory.getLogger(LedgerTransfer.class);

    /**
     * Armed between the two lock acquisitions.
     *
     * <p>The position is the whole point. A generic latency injector delays a
     * call; this delays the thread at one line - after the first
     * {@code SELECT ... FOR UPDATE} has committed this transaction to holding a
     * lock, and before the second one asks for the other. That window is
     * normally microseconds, which is why the deadlock is a production-only
     * event. Widening it to a second makes the deadlock happen every time,
     * which is the difference between a phase-7 experiment and a phase-7
     * anecdote.
     */
    public static final String SEAM = "ledger-transfer-locks";

    private final AccountRepository accounts;
    private final EntryRepository entries;
    private final ChaosSeams seams;
    private final boolean orderLocks;

    public LedgerTransfer(AccountRepository accounts,
                          EntryRepository entries,
                          ChaosSeams seams,
                          @Value("${payorch.ledger.transfer.lock-ordering:sorted}")
                          String lockOrdering) {
        this.accounts = accounts;
        this.entries = entries;
        this.seams = seams;
        this.orderLocks = !"declared".equalsIgnoreCase(lockOrdering);
    }

    /**
     * @param transferId the idempotency key, and the {@code event_id} on both
     *        entries - so a redelivered transfer collides on
     *        {@code uq_entry_event_account} exactly like a redelivered payment
     *        event does. The deadlock is the subject of this class; it is not an
     *        excuse to reintroduce a problem phase 6e solved
     * @throws InsufficientFunds if the source cannot cover the amount. Checked
     *         under the lock, which is the only place the answer is still true
     *         by the time it is used
     */
    @Transactional
    public void transfer(UUID transferId, String fromRef, String toRef,
                         String currency, long amountMinor) {

        if (amountMinor <= 0) {
            throw new IllegalArgumentException("a transfer amount must be positive");
        }
        if (fromRef.equals(toRef)) {
            // Not merely pointless: it would try to lock one row twice and then
            // post two entries with the same (event_id, account_id), which the
            // unique constraint refuses. Better to say why here than to hand
            // back a constraint violation.
            throw new IllegalArgumentException("a transfer needs two different accounts");
        }

        LedgerAccount from = require(fromRef, currency);
        LedgerAccount to = require(toRef, currency);

        // THE LINE THE WHOLE SUB-PHASE IS ABOUT.
        //
        // sorted:   both transactions take the lower id first, so no cycle can
        //           form and somebody always makes progress.
        // declared: locks are taken in the order the caller named the accounts,
        //           so transfer(A,B) and transfer(B,A) acquire in opposite
        //           orders and deadlock.
        List<LedgerAccount> lockOrder = orderLocks
                ? List.of(from, to).stream()
                        .sorted(Comparator.comparing(LedgerAccount::getId))
                        .toList()
                : List.of(from, to);

        lock(lockOrder.get(0));
        // Held one, about to ask for the other. Everything interesting happens
        // in this gap.
        seams.reach(SEAM);
        lock(lockOrder.get(1));

        // Re-read under the lock. The balance loaded before the lock was a value
        // anybody could have changed; this one cannot change until this
        // transaction commits, and that is the entire reason for taking the lock
        // rather than trusting the earlier read.
        long available = accounts.findById(from.getId())
                .map(LedgerAccount::getBalanceMinor)
                .orElseThrow(() -> new IllegalStateException("account vanished: " + fromRef));

        if (available < amountMinor) {
            throw new InsufficientFunds(fromRef, available, amountMinor);
        }

        entries.save(LedgerEntry.of(transferId, from.getId(), transferId,
                -amountMinor, currency, "TRANSFER_DEBIT"));
        entries.save(LedgerEntry.of(transferId, to.getId(), transferId,
                amountMinor, currency, "TRANSFER_CREDIT"));
        accounts.applyDelta(from.getId(), -amountMinor);
        accounts.applyDelta(to.getId(), amountMinor);
        entries.flush();

        log.info("ledger transfer",
                LogEvent.event()
                        .with(LogFields.AMOUNT_MINOR, amountMinor)
                        .with(LogFields.CURRENCY, currency)
                        .with(LogFields.OUTCOME, "TRANSFERRED")
                        .args());
    }

    /** Whether this instance takes its locks in a consistent order. */
    public boolean ordersLocks() {
        return orderLocks;
    }

    private void lock(LedgerAccount account) {
        accounts.lockById(account.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "account vanished while locking: " + account.getAccountRef()));
    }

    private LedgerAccount require(String accountRef, String currency) {
        return accounts.findByAccountRefAndCurrency(accountRef, currency)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no such account: " + accountRef + "/" + currency));
    }

    /**
     * The source could not cover the transfer.
     *
     * <p>Carries the numbers because both are this system's own ledger balances
     * - no card data can reach here, and an operator asking "why did this
     * transfer fail" wants exactly these two figures.
     */
    public static class InsufficientFunds extends RuntimeException {

        public InsufficientFunds(String accountRef, long available, long requested) {
            super("account " + accountRef + " holds " + available
                    + " minor units and cannot transfer " + requested);
        }
    }
}
