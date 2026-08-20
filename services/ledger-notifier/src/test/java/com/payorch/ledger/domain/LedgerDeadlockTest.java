package com.payorch.ledger.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.payorch.infra.chaos.ChaosSeam;
import com.payorch.infra.persistence.Uuid7;
import com.payorch.infra.chaos.ChaosSeams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 7e. A deadlock, on command - and then not.
 *
 * <h2>Why this can be a test rather than an anecdote</h2>
 *
 * <p>The window between taking one row lock and asking for the second is
 * normally microseconds wide, which is why lock-ordering bugs survive every test
 * suite and then happen in production at three in the morning. The
 * {@code chaos-core} seam sits in exactly that gap and widens it to half a
 * second, so both transactions are guaranteed to be holding one lock and wanting
 * the other at the same moment. The deadlock stops being probable and becomes
 * certain.
 *
 * <p>That is the difference the phase is asking for: <em>"a deadlock you can
 * reproduce on command is far more convincing than one you once saw"</em>.
 *
 * <h2>Both arms, in one context</h2>
 *
 * <p>The deadlocking implementation is kept reachable rather than deleted, the
 * way {@code EVENTS_PUBLISHER=direct} keeps phase 6's naive dual-write runnable.
 * Both beans are declared below so the before and the after are two assertions
 * in one file instead of a claim and a commit hash.
 *
 * <p>They are {@code @Bean}s and not {@code new LedgerTransfer(...)}, which
 * matters: {@code @Transactional} is implemented with a proxy, and a
 * hand-constructed instance gets none - the transfers would run with no
 * transaction, hold no locks, and this test would prove that a deadlock does not
 * happen when nothing locks anything. Phase 6d's bug, one layer over.
 *
 * <h2>H2, not InnoDB</h2>
 *
 * <p>H2 in MySQL mode detects the cycle and breaks it, which is enough to assert
 * the behaviour. It is <strong>not</strong> enough to quote
 * {@code SHOW ENGINE INNODB STATUS}, which the phase also asks for and which
 * needs the compose stack. That evidence is not in this repository yet and is
 * marked as such in the phase document rather than described from memory.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ledger-deadlock;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "payorch.ledger.listener-autostart=false",
        "payorch.ledger.bootstrap-servers=localhost:9092",
        // Shorter than H2's default, so the losing transaction gives up inside
        // the test rather than inside the reader's patience. A deadlock that
        // takes a minute to report is still a deadlock.
        "spring.jpa.properties.jakarta.persistence.lock.timeout=2000",
})
class LedgerDeadlockTest {

    private static final String CURRENCY = "INR";
    private static final String LEFT = "merchant:deadlock-left";
    private static final String RIGHT = "merchant:deadlock-right";

    @Autowired
    private LedgerTransfer sorted;

    @Autowired
    private LedgerTransfer declared;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private ChaosSeams seams;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void openFundedAccounts() {
        jdbc.sql("DELETE FROM ledger_entry").update();
        jdbc.sql("DELETE FROM ledger_account").update();

        open(LEFT, 100_000);
        open(RIGHT, 100_000);
    }

    @AfterEach
    void disarm() {
        seams.disarmAll();
    }

    /**
     * THE BEFORE. Two transfers between the same pair of accounts, in opposite
     * directions, at the same moment.
     *
     * <p>Each transaction takes the lock on the account the caller named first,
     * so one holds LEFT and wants RIGHT while the other holds RIGHT and wants
     * LEFT. Neither can proceed and neither will give up, so the database breaks
     * the cycle by killing one of them.
     *
     * <p>Note what this is <em>not</em>: it is not a lost update, not a wrong
     * balance, and not silent. A deadlock is the database refusing to let two
     * transactions corrupt each other - the failure is loud and the data is
     * intact. The problem is that the loud failure lands on a customer's
     * request, unpredictably, under exactly the load where it hurts most.
     */
    @Test
    void oppositeLockOrdersDeadlockOnCommand() throws Exception {
        seams.arm(LedgerTransfer.SEAM, ChaosSeam.pause(500));

        List<Throwable> failures = runBothDirections(declared);

        assertThat(failures)
                .as("one of the two transactions must have been killed to break the cycle")
                .hasSize(1);
        assertThat(rootCauseOf(failures.get(0)))
                .as("and it must be a locking failure, not something incidental")
                .containsIgnoringCase("lock");
    }

    /**
     * THE AFTER. The same two transfers, the same seam, the same half-second
     * window - and no deadlock, because both transactions now take the
     * lowest-numbered lock first.
     *
     * <p>The one holding it always makes progress, so no cycle can form. This is
     * the fix that scales: retry-on-deadlock also "works" and gets slower exactly
     * as contention rises, which is when it is needed most.
     */
    @Test
    void aConsistentLockOrderRemovesTheDeadlockEntirely() throws Exception {
        seams.arm(LedgerTransfer.SEAM, ChaosSeam.pause(500));

        List<Throwable> failures = runBothDirections(sorted);

        assertThat(failures)
                .as("with a consistent order there is no cycle to break")
                .isEmpty();
    }

    /**
     * And the money is where it should be. Equal transfers in both directions
     * net to nothing, which is a stronger statement than "no exception was
     * thrown" - a fix that serialized the transfers by losing one of them would
     * also produce no failures.
     */
    @Test
    void bothTransfersActuallyHappenUnderTheConsistentOrder() throws Exception {
        seams.arm(LedgerTransfer.SEAM, ChaosSeam.pause(200));

        runBothDirections(sorted);

        assertThat(balanceOf(LEFT)).isEqualTo(100_000);
        assertThat(balanceOf(RIGHT)).isEqualTo(100_000);
        assertThat(entryCount())
                .as("two transfers, two legs each")
                .isEqualTo(4);
    }

    /**
     * The condition an atomic delta cannot express, which is the whole reason
     * this path takes locks at all. Checked under the lock, where the answer is
     * still true by the time it is used.
     */
    @Test
    void aTransferIsRefusedWhenTheSourceCannotCoverIt() {
        assertThatThrownBy(() -> sorted.transfer(
                UUID.randomUUID(), LEFT, RIGHT, CURRENCY, 500_000))
                .isInstanceOf(LedgerTransfer.InsufficientFunds.class);

        assertThat(balanceOf(LEFT)).isEqualTo(100_000);
        assertThat(entryCount()).isZero();
    }

    /** The seam is inert unless armed - one hash lookup on the normal path. */
    @Test
    void theSeamCostsNothingWhenItIsNotArmed() {
        long startedAt = System.nanoTime();
        sorted.transfer(UUID.randomUUID(), LEFT, RIGHT, CURRENCY, 1_000);

        assertThat((System.nanoTime() - startedAt) / 1_000_000L).isLessThan(500L);
        assertThat(balanceOf(LEFT)).isEqualTo(99_000);
        assertThat(balanceOf(RIGHT)).isEqualTo(101_000);
    }

    /**
     * A redelivered transfer is still one transfer. The deadlock is this class's
     * subject; it is not an excuse to give up the idempotency phase 6e built.
     */
    @Test
    void aRepeatedTransferIdIsRefusedByTheUniqueConstraint() {
        UUID transferId = UUID.randomUUID();
        sorted.transfer(transferId, LEFT, RIGHT, CURRENCY, 1_000);

        assertThatThrownBy(() -> sorted.transfer(transferId, LEFT, RIGHT, CURRENCY, 1_000))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(entryCount()).isEqualTo(2);
    }

    // --- helpers ----------------------------------------------------------

    /**
     * Runs {@code transfer(LEFT, RIGHT)} and {@code transfer(RIGHT, LEFT)} at
     * the same moment and collects whatever came back.
     *
     * @return the failures, in whatever order they arrived
     */
    private List<Throwable> runBothDirections(LedgerTransfer transfer) throws Exception {
        List<Future<?>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            results.add(pool.submit(() ->
                    transfer.transfer(UUID.randomUUID(), LEFT, RIGHT, CURRENCY, 1_000)));
            results.add(pool.submit(() ->
                    transfer.transfer(UUID.randomUUID(), RIGHT, LEFT, CURRENCY, 1_000)));

            List<Throwable> failures = new ArrayList<>();
            for (Future<?> result : results) {
                try {
                    result.get(60, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
            return failures;
        }
    }

    private static String rootCauseOf(Throwable t) {
        Throwable cause = t;
        StringBuilder chain = new StringBuilder();
        while (cause != null) {
            chain.append(cause.getClass().getSimpleName()).append(": ")
                    .append(cause.getMessage()).append(" | ");
            cause = cause.getCause();
        }
        return chain.toString();
    }

    /**
     * Straight to SQL rather than through the repository, because
     * {@code applyDelta} is {@code @Modifying} and needs a transaction a
     * {@code @BeforeEach} does not have. Setting up state is also the one place
     * where bypassing the domain is honest: this is not exercising the write
     * path, it is arranging for the write path to have something to work on.
     */
    private void open(String accountRef, long balanceMinor) {
        jdbc.sql("""
                INSERT INTO ledger_account (id, account_ref, currency, balance_minor)
                VALUES (?, ?, ?, ?)
                """)
                .param(Uuid7.toBytes(Uuid7.generate()))
                .param(accountRef)
                .param(CURRENCY)
                .param(balanceMinor)
                .update();
    }

    private long balanceOf(String accountRef) {
        return accounts.findByAccountRefAndCurrency(accountRef, CURRENCY)
                .orElseThrow()
                .getBalanceMinor();
    }

    private long entryCount() {
        return jdbc.sql("SELECT COUNT(*) FROM ledger_entry").query(Long.class).single();
    }

    @TestConfiguration
    static class BothOrderings {

        /**
         * Both arms as Spring beans, so both get the {@code @Transactional}
         * proxy. Constructing them with {@code new} would leave them
         * transactionless - no transaction, no row locks held past the
         * statement, and no deadlock to observe. The test would pass by
         * measuring nothing.
         */
        @Bean
        LedgerTransfer sorted(AccountRepository accounts, EntryRepository entries,
                              ChaosSeams seams) {
            return new LedgerTransfer(accounts, entries, seams, "sorted");
        }

        @Bean
        LedgerTransfer declared(AccountRepository accounts, EntryRepository entries,
                                ChaosSeams seams) {
            return new LedgerTransfer(accounts, entries, seams, "declared");
        }
    }
}
