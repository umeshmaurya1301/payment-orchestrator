package com.payorch.edge.idempotency;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.payorch.infra.idempotency.ReplayableResponse;
import com.payorch.infra.persistence.Uuid7;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7c. Claims that expire, and records that do not live forever.
 *
 * <h2>What is actually at stake here</h2>
 *
 * <p>Taking over a claim is deciding that the request holding it is dead. Get
 * that wrong while it is merely slow and both requests run, which calls the
 * provider twice - the double charge the unique constraint was put there to
 * prevent, reintroduced at the recovery path. So most of these tests are about
 * the cases where a takeover must <em>not</em> happen, which is the half that
 * fails silently.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:idempotency-ttl;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "payorch.orchestrator.base-url=http://localhost:1",
        // The vault is a second datasource with its own credentials, and without
        // these the context tries to reach the real MySQL. Nothing here
        // tokenizes anything - the store is driven directly - but the controller
        // still has to be constructible.
        "payorch.vault.datasource.url=jdbc:h2:mem:idempotency-ttl-vault;DB_CLOSE_DELAY=-1",
        "payorch.vault.datasource.username=sa",
        "payorch.vault.datasource.password=",
        "payorch.vault.verify-on-startup=false",
        // Off, so a background run cannot delete a row a test is about to
        // assert on. The sweeper is driven directly below instead - a scheduled
        // job and a test racing over the same table is a flake nobody enjoys
        // diagnosing.
        "payorch.idempotency.sweep-initial-delay-ms=3600000",
})
class IdempotencyTtlTest {

    private static final UUID MERCHANT = UUID.fromString("0192abcd-0000-7000-8000-000000000001");

    @Autowired
    private JpaIdempotencyStore store;

    @Autowired
    private IdempotencyRecordRepository records;

    @Autowired
    private IdempotencySweeper sweeper;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM idempotency_record").update();
        jdbc.sql("DELETE FROM merchant").update();
        jdbc.sql("""
                INSERT INTO merchant (id, name, api_key_hash, status, created_at)
                VALUES (?, ?, ?, ?, ?)
                """)
                .param(Uuid7.toBytes(MERCHANT))
                .param("Test Merchant")
                .param("hash")
                .param("ACTIVE")
                .param(Timestamp.from(Instant.now()))
                .update();
    }

    // --- claim takeover ---------------------------------------------------

    /**
     * THE BUG 7C CLOSES.
     *
     * <p>A process that dies between claiming and completing leaves the row
     * claimed and unanswered. Before this, nothing ever cleaned it up: that key
     * was unusable forever, which is the precise opposite of what an idempotency
     * key is for - the caller does not know what happened and wants to retry
     * safely.
     */
    @Test
    void anAbandonedClaimCanBeTakenOverOnceItsWindowHasPassed() {
        assertThat(store.claim(MERCHANT, "dead-key", "fingerprint-a")).isTrue();
        expireClaim("dead-key");

        assertThat(store.claim(MERCHANT, "dead-key", "fingerprint-b"))
                .as("the key must become usable again")
                .isTrue();
    }

    /**
     * THE CASE THAT MUST NOT HAPPEN. A claim inside its window belongs to a
     * request that may still be running, and taking it over would call the
     * provider twice for one payment.
     */
    @Test
    void aClaimStillInsideItsWindowIsNeverTakenOver() {
        assertThat(store.claim(MERCHANT, "live-key", "fingerprint-a")).isTrue();

        assertThat(store.claim(MERCHANT, "live-key", "fingerprint-b"))
                .as("a claim whose owner may still be alive is not available")
                .isFalse();
    }

    /**
     * A completed record is an answer somebody may still be replaying, so it is
     * never taken over however old it is. Expiry for a completed record means
     * "may be deleted", not "may be overwritten" - two different lifecycles on
     * one row, which is exactly where this kind of bug lives.
     */
    @Test
    void aCompletedRecordIsNeverTakenOverEvenWhenOld() {
        store.claim(MERCHANT, "done-key", "fingerprint-a");
        store.complete(MERCHANT, "done-key", response("{\"id\":\"first\"}"));
        expireClaim("done-key");

        assertThat(store.claim(MERCHANT, "done-key", "fingerprint-b")).isFalse();
        assertThat(store.find(MERCHANT, "done-key"))
                .get()
                .satisfies(existing -> assertThat(existing.response()).isPresent());
    }

    /**
     * A row written before 7c has no {@code claim_expires_at} - no opinion about
     * when its owner would be dead - and taking over a claim on no opinion is
     * how a live request gets duplicated. They are grandfathered as
     * never-expiring rather than retroactively declared abandoned.
     */
    @Test
    void aClaimFromBeforeThisPhaseIsNotTakenOverOnAGuess() {
        store.claim(MERCHANT, "legacy-key", "fingerprint-a");
        jdbc.sql("UPDATE idempotency_record SET claim_expires_at = NULL WHERE idempotency_key = ?")
                .param("legacy-key")
                .update();

        assertThat(store.claim(MERCHANT, "legacy-key", "fingerprint-b")).isFalse();
    }

    /**
     * The new owner gets a fresh window. Without this, the second taker would
     * inherit an already-expired one and a third could take it straight off
     * them - a claim nobody can hold, which is the same unusable key by another
     * route.
     */
    @Test
    void takingOverAClaimResetsItsWindow() {
        store.claim(MERCHANT, "dead-key", "fingerprint-a");
        expireClaim("dead-key");
        store.claim(MERCHANT, "dead-key", "fingerprint-b");

        assertThat(records.findByMerchantIdAndIdempotencyKey(MERCHANT, "dead-key"))
                .get()
                .satisfies(record -> assertThat(record.getClaimExpiresAt()).isAfter(Instant.now()));

        assertThat(store.claim(MERCHANT, "dead-key", "fingerprint-c"))
                .as("the new owner must be protected the way the old one was")
                .isFalse();
    }

    /**
     * The taker owns the key now, so the fingerprint moves with it. Leaving the
     * dead request behind would make every duplicate of the LIVE request look
     * like a key reuse and answer 422.
     */
    @Test
    void takingOverAClaimAdoptsTheNewFingerprint() {
        store.claim(MERCHANT, "dead-key", "fingerprint-a");
        expireClaim("dead-key");
        store.claim(MERCHANT, "dead-key", "fingerprint-b");

        assertThat(store.find(MERCHANT, "dead-key"))
                .get()
                .satisfies(existing -> assertThat(existing.fingerprint()).isEqualTo("fingerprint-b"));
    }

    /**
     * Two requests arriving at one abandoned claim: exactly one may take it.
     *
     * <p>Sequential here because the guarantee comes from the conditional
     * UPDATE matching a single row, not from timing - a read-then-write
     * implementation would let both callers see the same expired claim and both
     * proceed, and this second call is what catches that.
     */
    @Test
    void onlyOneCallerMayTakeOverAnAbandonedClaim() {
        store.claim(MERCHANT, "dead-key", "fingerprint-a");
        expireClaim("dead-key");

        assertThat(store.claim(MERCHANT, "dead-key", "fingerprint-b")).isTrue();
        assertThat(store.claim(MERCHANT, "dead-key", "fingerprint-c"))
                .as("the second taker must lose to the first")
                .isFalse();
    }

    // --- the sweeper ------------------------------------------------------

    @Test
    void completingARecordSetsItsRetentionWindow() {
        store.claim(MERCHANT, "done-key", "fingerprint-a");
        store.complete(MERCHANT, "done-key", response("{}"));

        assertThat(records.findByMerchantIdAndIdempotencyKey(MERCHANT, "done-key"))
                .get()
                .satisfies(record -> {
                    assertThat(record.getExpiresAt()).isAfter(Instant.now());
                    assertThat(record.getClaimExpiresAt())
                            .as("a completed record carries no claim window")
                            .isNull();
                });
    }

    @Test
    void theSweeperDeletesRecordsPastTheirRetentionWindow() {
        store.claim(MERCHANT, "old-key", "fingerprint-a");
        store.complete(MERCHANT, "old-key", response("{}"));
        expireRetention("old-key");

        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(records.findByMerchantIdAndIdempotencyKey(MERCHANT, "old-key")).isEmpty();
    }

    /** A record still inside its window is an answer somebody may replay. */
    @Test
    void theSweeperLeavesRecordsInsideTheirWindowAlone() {
        store.claim(MERCHANT, "fresh-key", "fingerprint-a");
        store.complete(MERCHANT, "fresh-key", response("{}"));

        assertThat(sweeper.sweep()).isZero();
        assertThat(records.findByMerchantIdAndIdempotencyKey(MERCHANT, "fresh-key")).isPresent();
    }

    /**
     * An in-flight claim has no retention window at all, and must survive a
     * sweep. A cleanup job that deleted a live claim would hand the key to a
     * second request while the first was still running.
     */
    @Test
    void theSweeperNeverTouchesAnInFlightClaim() {
        store.claim(MERCHANT, "live-key", "fingerprint-a");

        assertThat(sweeper.sweep()).isZero();
        assertThat(records.findByMerchantIdAndIdempotencyKey(MERCHANT, "live-key")).isPresent();
    }

    /**
     * Records written before 7c have no {@code expires_at} and are left alone
     * rather than swept on a guess. They are a finite, shrinking population, and
     * deleting an answer somebody is still entitled to replay is not worth doing
     * on an assumption.
     */
    @Test
    void theSweeperLeavesRecordsFromBeforeThisPhaseAlone() {
        store.claim(MERCHANT, "legacy-key", "fingerprint-a");
        store.complete(MERCHANT, "legacy-key", response("{}"));
        jdbc.sql("UPDATE idempotency_record SET expires_at = NULL WHERE idempotency_key = ?")
                .param("legacy-key")
                .update();

        assertThat(sweeper.sweep()).isZero();
        assertThat(records.findByMerchantIdAndIdempotencyKey(MERCHANT, "legacy-key")).isPresent();
    }

    /**
     * A backlog larger than one batch is cleared across several, not in one
     * unbounded DELETE holding locks on the table every payment writes to.
     */
    @Test
    void theSweeperClearsABacklogInBoundedBatches() {
        for (int i = 0; i < 12; i++) {
            String key = "old-key-" + i;
            store.claim(MERCHANT, key, "fingerprint-" + i);
            store.complete(MERCHANT, key, response("{}"));
            expireRetention(key);
        }

        assertThat(sweeper.sweep()).isEqualTo(12);
        assertThat(records.count()).isZero();
    }

    // --- helpers ----------------------------------------------------------

    /** Ages a claim past its window, the way a dead process would leave one. */
    private void expireClaim(String key) {
        jdbc.sql("UPDATE idempotency_record SET claim_expires_at = ? WHERE idempotency_key = ?")
                .param(Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))))
                .param(key)
                .update();
    }

    private void expireRetention(String key) {
        jdbc.sql("UPDATE idempotency_record SET expires_at = ? WHERE idempotency_key = ?")
                .param(Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))))
                .param(key)
                .update();
    }

    private static ReplayableResponse response(String json) {
        return new ReplayableResponse(201, "application/json",
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
