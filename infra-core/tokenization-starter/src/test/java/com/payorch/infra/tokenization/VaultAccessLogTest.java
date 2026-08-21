package com.payorch.infra.tokenization;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every card read leaves a record, including the reads that failed. Phase 9c.
 *
 * <h2>What this covers that the live script cannot</h2>
 *
 * <p>{@code tools/security/vault-audit.sh} proves the credential boundary and
 * completeness under load against the real stack. What it cannot easily produce
 * is a <em>broken audit table</em>, and that is the case the design decision
 * turns on: when the record cannot be written, does the card come back anyway?
 *
 * <p>Fail-closed says no. It is the uncomfortable choice — it puts a second
 * write in front of every decryption and turns an audit-table problem into a
 * payment failure — and it is chosen because the alternative fails in a worse
 * place. A log skipped when writing it is inconvenient has its gap exactly where
 * the incident is, since the conditions that break the write are correlated with
 * the conditions worth investigating.
 */
class VaultAccessLogTest {

    private static final String SCOPE = "merchant-under-test";
    private static final String PAN = "4242424242424242";

    private static final String VAULT_SCHEMA = """
            CREATE TABLE token_vault (
                token        VARCHAR(48)   NOT NULL PRIMARY KEY,
                pan_iv       VARBINARY(12) NOT NULL,
                pan_cipher   VARBINARY(64) NOT NULL,
                wrapped_dek  VARBINARY(64) NULL,
                dek_iv       VARBINARY(12) NULL,
                key_scope    VARCHAR(64)   NULL,
                kek_version  VARCHAR(32)   NULL,
                bin          CHAR(6)       NOT NULL,
                last4        CHAR(4)       NOT NULL,
                expiry_month TINYINT       NOT NULL,
                expiry_year  SMALLINT      NOT NULL
            )
            """;

    private static final String AUDIT_SCHEMA = """
            CREATE TABLE vault_access_log (
                id             BIGINT AUTO_INCREMENT PRIMARY KEY,
                token          VARCHAR(64) NOT NULL,
                actor          VARCHAR(64) NOT NULL,
                purpose        VARCHAR(64) NOT NULL,
                reference      VARCHAR(64) NULL,
                correlation_id VARCHAR(64) NULL,
                trace_id       VARCHAR(64) NULL,
                outcome        VARCHAR(24) NOT NULL,
                at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;

    private VaultConnection connection;
    private TokenVault vault;
    private EnvelopeCipher envelope;

    @BeforeEach
    void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:audit-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        connection = new VaultConnection(new HikariDataSource(config));
        connection.jdbc().sql(VAULT_SCHEMA).update();
        connection.jdbc().sql(AUDIT_SCHEMA).update();

        String key = Base64.getEncoder().encodeToString(new byte[32]);
        envelope = new EnvelopeCipher(new KeyRing(new InMemoryKekStore(Map.of("v1", key), "v1")));
        vault = new TokenVault(connection.jdbc(), new PanCipher(key), envelope);
        vault.setAuditLog(new VaultAccessLog(connection.jdbc(), "psp-connector-test", true));
    }

    @AfterEach
    void close() {
        connection.close();
    }

    private String store() {
        return vault.tokenize(PAN, 12, 2030, SCOPE).token();
    }

    private List<Map<String, Object>> auditRows() {
        return connection.jdbc()
                .sql("SELECT token, actor, purpose, reference, outcome FROM vault_access_log ORDER BY id")
                .query()
                .listOfRows();
    }

    // ---------------------------------------------------------------------
    // EVERY OUTCOME IS RECORDED, NOT JUST THE HAPPY ONE
    // ---------------------------------------------------------------------

    @Test
    void aSuccessfulReadIsRecordedOnce() {
        String token = store();

        vault.detokenize(token, VaultAccess.forPayment("authorize", "payment-1"));

        assertThat(auditRows()).singleElement().satisfies(row -> {
            assertThat(row.get("TOKEN")).isEqualTo(token);
            assertThat(row.get("ACTOR")).isEqualTo("psp-connector-test");
            assertThat(row.get("PURPOSE")).isEqualTo("authorize");
            assertThat(row.get("REFERENCE")).isEqualTo("payment-1");
            assertThat(row.get("OUTCOME")).isEqualTo(VaultAccessLog.SUCCESS);
        });
    }

    /**
     * The row an investigation actually wants. A successful read by an
     * authorised service is the boring case; a run of these from one actor is
     * somebody walking the token space, and nothing else in this system would
     * show it — the connector answers 400 and the orchestrator records a
     * rejected payment, neither of which says "a card lookup was attempted".
     */
    @Test
    void aTokenThatDoesNotExistIsStillRecorded() {
        assertThatThrownBy(() -> vault.detokenize("tok_never_issued", VaultAccess.unattributed()))
                .isInstanceOf(TokenVault.UnknownTokenException.class);

        assertThat(auditRows()).singleElement().satisfies(row -> {
            assertThat(row.get("TOKEN")).isEqualTo("tok_never_issued");
            assertThat(row.get("OUTCOME")).isEqualTo(VaultAccessLog.UNKNOWN_TOKEN);
            assertThat(row.get("PURPOSE")).isEqualTo("unattributed");
        });
    }

    /**
     * A row whose key has been destroyed — a completed erasure — is a read that
     * failed for a reason worth seeing. Somebody is still asking for a card that
     * was shredded, which means a copy of the token outlived the erasure.
     */
    @Test
    void aReadThatCannotBeDecryptedIsRecordedAsFailed() {
        String token = store();
        // Point the vault at a key ring that no longer has the version this row
        // names: exactly what a crypto-shred leaves behind.
        TokenVault shredded = new TokenVault(connection.jdbc(), null,
                new EnvelopeCipher(new KeyRing(new InMemoryKekStore(Map.of("v2",
                        Base64.getEncoder().encodeToString(new byte[32])), "v2"))));
        shredded.setAuditLog(new VaultAccessLog(connection.jdbc(), "psp-connector-test", true));

        assertThatThrownBy(() -> shredded.detokenize(token, VaultAccess.forPayment("authorize", "p2")))
                .isInstanceOf(RuntimeException.class);

        assertThat(auditRows()).singleElement()
                .satisfies(row -> assertThat(row.get("OUTCOME")).isEqualTo(VaultAccessLog.FAILED));
    }

    // ---------------------------------------------------------------------
    // THE DECISION: WHAT HAPPENS WHEN THE LOG ITSELF IS BROKEN
    // ---------------------------------------------------------------------

    @Test
    void failClosedRefusesTheCardWhenTheAuditWriteFails() {
        String token = store();
        connection.jdbc().sql("DROP TABLE vault_access_log").update();

        assertThatThrownBy(() -> vault.detokenize(token, VaultAccess.forPayment("authorize", "p3")))
                .as("no card leaves this method unrecorded")
                .isInstanceOf(VaultAccessLog.AuditUnavailableException.class);
    }

    /**
     * The control arm, and it exists to be measured rather than to be used.
     * {@code fail-closed=false} keeps payments flowing through an audit outage
     * and produces exactly the gap this whole feature was built to close, which
     * is why the default is the other way round.
     */
    @Test
    void failOpenReturnsTheCardAndLosesTheRecord() {
        String token = store();
        vault.setAuditLog(new VaultAccessLog(connection.jdbc(), "psp-connector-test", false));
        connection.jdbc().sql("DROP TABLE vault_access_log").update();

        DetokenizedCard card = vault.detokenize(token, VaultAccess.forPayment("authorize", "p4"));

        assertThat(card.pan())
                .as("the card came back, and nothing recorded that it did")
                .isEqualTo(PAN);
    }

    /**
     * The exception must name the audit table rather than the card, because the
     * operational response is completely different: nothing is wrong with the
     * key or the vault row, and somebody paged at 3am needs to know that before
     * they start investigating a crypto problem that does not exist.
     */
    @Test
    void theFailureSaysWhatIsActuallyBroken() {
        String token = store();
        connection.jdbc().sql("DROP TABLE vault_access_log").update();

        assertThatThrownBy(() -> vault.detokenize(token, VaultAccess.unattributed()))
                .hasMessageContaining("audited")
                .hasMessageContaining("not read");
    }

    // ---------------------------------------------------------------------
    // THE LOG MUST NOT BECOME A SECOND COPY OF THE CARD
    // ---------------------------------------------------------------------

    /**
     * An audit log that records what was read is another place the PAN lives,
     * and it is the copy nobody remembers to encrypt — it has no DEK, no KEK
     * version, and is exempt from the erasure that crypto-shredding provides.
     * Everything here is a reference to a card rather than a card.
     */
    @Test
    void noAuditColumnEverContainsThePan() {
        String token = store();
        vault.detokenize(token, VaultAccess.forPayment("authorize", "p5"));

        List<Map<String, Object>> rows = connection.jdbc()
                .sql("SELECT * FROM vault_access_log").query().listOfRows();

        assertThat(rows).allSatisfy(row -> row.values().forEach(value ->
                assertThat(String.valueOf(value))
                        .as("no column may contain the card number or any part of it")
                        .doesNotContain(PAN)
                        .doesNotContain(PAN.substring(0, 6))
                        .doesNotContain(PAN.substring(12))));
    }

    /**
     * A vault with no audit log configured still works. payments-edge tokenizes
     * and never detokenizes, so it has no grant on the audit table at all —
     * wiring the log in unconditionally would make the edge fail on a table it
     * is not supposed to write to.
     */
    @Test
    void aVaultWithNoAuditLogStillReads() {
        String token = store();
        // The SAME cipher, not a fresh one. Key material is per scope, and a new
        // KeyRing has never created a key for this merchant - which is the
        // shredded case, tested above, and not the case under test here.
        TokenVault unaudited = new TokenVault(connection.jdbc(), null, envelope);

        assertThat(unaudited.detokenize(token).pan()).isEqualTo(PAN);
        assertThat(auditRows()).isEmpty();
    }
}
