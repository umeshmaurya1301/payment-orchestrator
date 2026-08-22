package com.payorch.edge.merchant;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.infra.persistence.Uuid7;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rotating an API key without an outage. Phase 9b.
 *
 * <h2>The failure this removes</h2>
 *
 * <p>Keys have been hashed at rest since phase 1, so a dump of the database has
 * never yielded a usable credential. That is worth having and it is not what
 * limits exposure. A merchant had exactly <em>one</em> key, so replacing it was
 * a simultaneous edit on two sides — ours and theirs — and every honest version
 * of that procedure has a window in which their requests fail.
 *
 * <p>A credential that cannot be replaced without an outage is a credential
 * nobody replaces. It then lives for years, in their CI logs, on a laptop that
 * left with an employee, in a screenshot in a support ticket. <strong>The
 * rotation window is the exposure, not the hashing.</strong>
 *
 * <h2>What is asserted</h2>
 *
 * <p>The whole procedure, in the order an operator performs it, plus the two
 * ways it goes wrong: revoking too early, and never finishing at all.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:edge-rotation;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "payorch.orchestrator.base-url=http://localhost:1",
        "payorch.vault.datasource.url=jdbc:h2:mem:rotation-vault;DB_CLOSE_DELAY=-1",
        "payorch.vault.datasource.username=sa",
        "payorch.vault.datasource.password=",
        "payorch.vault.verify-on-startup=false",
        // Zero, so the usage stamp is written on the first request rather than
        // once a minute. Production runs at 60s for the reason ApiKeyUsageRecorder
        // gives; a test that waited for that would be testing the clock.
        "payorch.api-keys.usage-stamp-interval-ms=0",
})
@AutoConfigureMockMvc
class ApiKeyRotationTest {

    private static final UUID MERCHANT = UUID.fromString("0192abcd-0000-7000-8000-0000000000a1");
    private static final String OLD_KEY = "pk_test_the_old_key";
    private static final String NEW_KEY = "pk_test_the_new_key";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MerchantApiKeyRepository keys;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM merchant_api_key").update();
        jdbc.sql("DELETE FROM merchant").update();
        jdbc.sql("""
                INSERT INTO merchant (id, name, status, created_at) VALUES (?, ?, ?, ?)
                """)
                .param(Uuid7.toBytes(MERCHANT)).param("Rotating Merchant")
                .param("ACTIVE").param(Timestamp.from(Instant.now()))
                .update();
        issue(OLD_KEY, "original", "ACTIVE", null);
    }

    private void issue(String apiKey, String label, String status, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO merchant_api_key
                    (id, merchant_id, api_key_hash, label, status, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
                .param(Uuid7.toBytes(Uuid7.generate()))
                .param(Uuid7.toBytes(MERCHANT))
                .param(ApiKeyAuthFilter.sha256Hex(apiKey))
                .param(label).param(status)
                .param(Timestamp.from(Instant.now()))
                .param(expiresAt == null ? null : Timestamp.from(expiresAt))
                .update();
    }

    private void setStatus(String label, String status, Instant expiresAt) {
        jdbc.sql("UPDATE merchant_api_key SET status = ?, expires_at = ? WHERE label = ?")
                .param(status)
                .param(expiresAt == null ? null : Timestamp.from(expiresAt))
                .param(label)
                .update();
    }

    /** Any authenticated endpoint will do; this asserts about the credential. */
    private int callWith(String apiKey) throws Exception {
        return mvc.perform(get("/v1/payments/" + UUID.randomUUID())
                        .header(ApiKeyAuthFilter.HEADER, apiKey))
                .andReturn().getResponse().getStatus();
    }

    private boolean authenticated(int status) {
        // 404 means the credential was accepted and the payment did not exist,
        // which is the point: anything other than 401 means authentication
        // passed. Asserting on 404 specifically would couple this test to what
        // the lookup endpoint does.
        return status != 401;
    }

    // ---------------------------------------------------------------------
    // THE PROCEDURE
    // ---------------------------------------------------------------------

    @Test
    void beforeRotationTheOriginalKeyWorks() throws Exception {
        assertThat(authenticated(callWith(OLD_KEY))).isTrue();
        assertThat(callWith(NEW_KEY)).isEqualTo(401);
    }

    /**
     * Step one, and the entire reason for the table. Both keys authenticate at
     * once, so the merchant can deploy the new one whenever they like rather
     * than at a moment we dictate.
     */
    @Test
    void duringTheOverlapBothKeysWork() throws Exception {
        issue(NEW_KEY, "rotated-2026-08", "ACTIVE", null);
        setStatus("original", "RETIRING", Instant.now().plus(Duration.ofDays(7)));

        assertThat(authenticated(callWith(OLD_KEY)))
                .as("the old key must keep working, or the rotation is an outage")
                .isTrue();
        assertThat(authenticated(callWith(NEW_KEY))).isTrue();
    }

    @Test
    void afterRevocationOnlyTheNewKeyWorks() throws Exception {
        issue(NEW_KEY, "rotated-2026-08", "ACTIVE", null);
        setStatus("original", "REVOKED", null);

        assertThat(callWith(OLD_KEY)).isEqualTo(401);
        assertThat(authenticated(callWith(NEW_KEY))).isTrue();
    }

    // ---------------------------------------------------------------------
    // THE TWO WAYS IT GOES WRONG
    // ---------------------------------------------------------------------

    /**
     * A rotation started and never finished. Without an expiry the overlap is
     * not a window — it is two permanent credentials and twice the exposure,
     * which is a worse position than the single key it replaced.
     *
     * <p>The expiry is enforced on read rather than by a job that flips
     * RETIRING to REVOKED, because that job is exactly the kind nobody notices
     * has stopped. Here the window closes with nothing running.
     */
    @Test
    void anExpiredOverlapClosesItselfWithNoJobRunning() throws Exception {
        issue(NEW_KEY, "rotated-2026-08", "ACTIVE", null);
        setStatus("original", "RETIRING", Instant.now().minus(Duration.ofSeconds(1)));

        assertThat(callWith(OLD_KEY))
                .as("still RETIRING in the database, and past its expiry")
                .isEqualTo(401);
        assertThat(jdbc.sql("SELECT status FROM merchant_api_key WHERE label = 'original'")
                .query(String.class).single())
                .as("nothing flipped the row; the check is on the read path")
                .isEqualTo("RETIRING");

        assertThat(authenticated(callWith(NEW_KEY))).isTrue();
    }

    /**
     * Revoking too early. This is the outage the whole feature exists to
     * prevent, so it is worth showing it still happens if the operator ignores
     * the evidence — the mechanism removes the <em>need</em> to guess, it does
     * not remove the ability to.
     */
    @Test
    void revokingBeforeTheMerchantMigratedStillBreaksThem() throws Exception {
        issue(NEW_KEY, "rotated-2026-08", "ACTIVE", null);
        setStatus("original", "REVOKED", null);

        assertThat(callWith(OLD_KEY))
                .as("which is why last_used_at exists: so nobody has to guess")
                .isEqualTo(401);
    }

    // ---------------------------------------------------------------------
    // THE EVIDENCE THAT MAKES REVOCATION SAFE
    // ---------------------------------------------------------------------

    @Test
    void usingAKeyStampsIt() throws Exception {
        assertThat(keys.findByApiKeyHash(ApiKeyAuthFilter.sha256Hex(OLD_KEY)).orElseThrow()
                .getLastUsedAt())
                .as("a key nobody has used yet")
                .isNull();

        Instant before = Instant.now().minusSeconds(1);
        callWith(OLD_KEY);

        assertThat(keys.findByApiKeyHash(ApiKeyAuthFilter.sha256Hex(OLD_KEY)).orElseThrow()
                .getLastUsedAt())
                .as("this is what tells an operator the old key is safe to revoke")
                .isNotNull()
                .isAfter(before);
    }

    /**
     * A rejected key is not stamped. Otherwise every attempt with a revoked
     * credential would keep refreshing the very timestamp used to decide whether
     * it is still in use — the number would say "in active use" forever, and
     * would say it most loudly when the key is being abused.
     */
    @Test
    void aRejectedKeyIsNotStamped() throws Exception {
        setStatus("original", "REVOKED", null);
        callWith(OLD_KEY);

        assertThat(keys.findByApiKeyHash(ApiKeyAuthFilter.sha256Hex(OLD_KEY)).orElseThrow()
                .getLastUsedAt())
                .isNull();
    }

    // ---------------------------------------------------------------------
    // WHAT MUST NOT CHANGE
    // ---------------------------------------------------------------------

    /**
     * The credential is still never stored. Rotation added a table; it did not
     * add a place the plaintext key lives.
     */
    @Test
    void noTableColumnContainsThePlaintextKey() {
        assertThat(jdbc.sql("SELECT api_key_hash FROM merchant_api_key WHERE label = 'original'")
                .query(String.class).single())
                .isNotEqualTo(OLD_KEY)
                .hasSize(64)
                .isEqualTo(ApiKeyAuthFilter.sha256Hex(OLD_KEY));
    }

    /**
     * A suspended merchant is refused whichever key is presented. The key's own
     * status is not the only gate, and a rotation must not become a way to
     * reactivate a merchant we turned off.
     */
    @Test
    void aSuspendedMerchantIsRefusedWithAPerfectlyValidKey() throws Exception {
        jdbc.sql("UPDATE merchant SET status = 'SUSPENDED' WHERE id = ?")
                .param(Uuid7.toBytes(MERCHANT)).update();

        assertThat(callWith(OLD_KEY)).isEqualTo(401);
    }
}
