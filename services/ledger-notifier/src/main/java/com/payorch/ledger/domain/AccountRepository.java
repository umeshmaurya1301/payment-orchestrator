package com.payorch.ledger.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<LedgerAccount, UUID> {

    Optional<LedgerAccount> findByAccountRefAndCurrency(String accountRef, String currency);

    /**
     * Applies one leg to a balance, <strong>in the database</strong>.
     *
     * <h2>Why this is not {@code account.apply(delta)} on a managed entity</h2>
     *
     * <p>It was, and it silently lost money. Read-modify-write on an entity is
     * two operations: JPA loads the row into a snapshot, the caller adds a
     * number in Java, and the flush writes the total back. Under REPEATABLE READ
     * the load takes no lock, so two consumer threads posting to the same
     * account both read {@code X}, compute {@code X+a} and {@code X+b}, and the
     * second write overwrites the first. One leg vanishes.
     *
     * <p>It is not hypothetical and it is not rare. The ledger container runs
     * three consumer threads across six partitions, and every single payment
     * touches {@code settlement:clearing} - so the hottest row in the schema is
     * also the one every posting races on. Measured in phase 6j, after the
     * phase-6 experiments had been running for two days:
     *
     * <pre>
     *   account                cached_balance   sum_of_entries       drift
     *   merchant:0192abcd...        4,368,000        6,279,000  -1,911,000
     *   settlement:clearing        -4,326,000       -6,220,200   1,894,200
     *   settlement:card-network       -42,000          -58,800      16,800
     * </pre>
     *
     * <p>The entries were right the whole time. {@code SUM(amount_minor)} over
     * the whole table was zero on every check, and phase 6e's convergence
     * assertion passed every run, because <strong>the double-entry invariant is
     * computed over the entries and the drift is in a cache beside them</strong>.
     * The books balanced and the balances were wrong.
     *
     * <p>{@code balance = balance + :delta} in SQL is one statement. InnoDB takes
     * the row's exclusive lock for its duration and the arithmetic happens inside
     * that lock, so concurrent postings serialize instead of overwriting.
     *
     * <p><strong>What it costs, stated rather than discovered later:</strong>
     * every posting now contends on the clearing row. That is the correct trade -
     * a contended balance is a throughput problem and a lost update is a
     * correctness one - and it is the reason real ledgers eventually shard the
     * clearing account or stop keeping a running balance at all and sum the
     * entries on demand.
     */
    @Modifying
    @Query("update LedgerAccount a set a.balanceMinor = a.balanceMinor + :delta where a.id = :id")
    int applyDelta(@Param("id") UUID id, @Param("delta") long delta);

    /**
     * Locks one account for the rest of the transaction. Phase 7e.
     *
     * <h2>Why a pessimistic lock exists here at all, when applyDelta does not
     * need one</h2>
     *
     * <p>{@link #applyDelta} is the right tool for a posting and needs no lock
     * from the application: the row lock InnoDB takes for the duration of the
     * {@code UPDATE} is the critical section, and there is no round trip between
     * the read and the write for anything to happen in. It is strictly better
     * than {@code SELECT ... FOR UPDATE} for that case.
     *
     * <p>What it cannot express is a <strong>condition</strong>. "Move this
     * amount only if the source has it" needs the balance read, a decision made
     * in Java, and the write applied - with nothing else allowed to move the
     * balance in between. That is what a transfer does, and it is the case
     * pessimistic locking is actually for.
     *
     * <p>{@code PESSIMISTIC_WRITE} rather than {@code PESSIMISTIC_READ}: another
     * transaction must not be able to read this balance and make its own
     * decision on it while this one is deciding.
     *
     * <p><strong>Two of these in one transaction is where deadlocks live</strong>,
     * which is the entire subject of phase 7e. See {@code LedgerTransfer}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LedgerAccount a where a.id = :id")
    Optional<LedgerAccount> lockById(@Param("id") UUID id);

    /**
     * Every account whose cached balance disagrees with its entries.
     *
     * <p>The check that would have caught the above on day one, and it costs a
     * single query. A denormalized total is a cache, and an uncheckable cache is
     * a rumour.
     */
    @Query("""
            select new com.payorch.ledger.domain.AccountRepository$Drift(
                a.id, a.accountRef, a.balanceMinor,
                coalesce((select sum(e.amountMinor) from LedgerEntry e where e.accountId = a.id), 0L))
            from LedgerAccount a
            """)
    List<Drift> drift();

    /** @param cached what the account row says; @param actual what the entries say */
    record Drift(UUID id, String accountRef, long cached, long actual) {
        public long delta() {
            return cached - actual;
        }
    }
}
