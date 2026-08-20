package com.payorch.infra.tokenization;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
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
 * Phase 9b's exit criterion, against a real table: <em>a demonstrated KEK
 * rotation — old records still readable, no bulk rewrite</em>.
 *
 * <p>{@code EnvelopeCipherTest} proves the cryptography. This proves the
 * <strong>operation</strong>: that the rotation reads and writes only key
 * material, that every card ciphertext in the table is byte-identical
 * afterwards, and that the vault stays readable at every point in between.
 */
class VaultRotationTest {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS token_vault (
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
                expiry_year  SMALLINT      NOT NULL,
                created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;

    private static final String KEK_V1 = key();
    private static final String KEK_V2 = key();

    private VaultConnection connection;
    private TokenVault vaultOnV1;
    private TokenVault vaultOnV2;
    private VaultRotation rotation;

    private static String key() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static Map<String, String> ring(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @BeforeEach
    void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:rotation-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        connection = new VaultConnection(new HikariDataSource(config));
        connection.jdbc().sql(SCHEMA).update();

        EnvelopeCipher onV1 = new EnvelopeCipher(new KeyRing(new InMemoryKekStore(ring("v1", KEK_V1), "v1")));

        // Both versions on the ring, v2 current. This is the state a rotation
        // runs in - the old key stays until every row has moved off it.
        EnvelopeCipher onV2 = new EnvelopeCipher(
                new KeyRing(new InMemoryKekStore(ring("v1", KEK_V1, "v2", KEK_V2), "v2")));

        vaultOnV1 = new TokenVault(connection.jdbc(), null, onV1);
        vaultOnV2 = new TokenVault(connection.jdbc(), null, onV2);
        rotation = new VaultRotation(connection.jdbc(), onV2, 100);
    }

    @AfterEach
    void tearDown() {
        connection.close();
    }

    private List<String> tokenizeAll(String... pans) {
        List<String> tokens = new ArrayList<>();
        for (String pan : pans) {
            tokens.add(vaultOnV1.tokenize(pan, 12, 2030, KeyRing.SHARED_SCOPE).token());
        }
        return tokens;
    }

    /**
     * Every card's stored bytes, base64 encoded.
     *
     * <p>Base64 rather than {@code byte[]}, and that is not cosmetic: a
     * {@code Map<String, byte[]>} compares its values by REFERENCE, so two maps
     * holding identical bytes are never equal and the assertion below would fail
     * whether or not the rotation had rewritten anything. Measured - the first
     * version of this test failed for exactly that reason and said nothing about
     * the rotation at all.
     */
    private Map<String, String> ciphertextsByToken() {
        return connection.jdbc().sql("SELECT token, pan_cipher, pan_iv FROM token_vault")
                .query((rs, n) -> Map.entry(rs.getString("token"),
                        Base64.getEncoder().encodeToString(
                                concat(rs.getBytes("pan_iv"), rs.getBytes("pan_cipher")))))
                .list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // --- the exit criterion -------------------------------------------------

    /**
     * THE ONE THE PHASE ASKS FOR.
     *
     * <p>Rotate the KEK. Every card must still be readable, and not one card
     * ciphertext may have changed - because if any had, the rotation would have
     * decrypted it, and the entire argument for envelope encryption would be
     * hollow.
     */
    @Test
    void aKekRotationRewritesNoCardCiphertextAndLosesNoCard() {
        List<String> tokens = tokenizeAll(
                "4242424242424242", "4111111111111111", "5555555555554444");
        Map<String, String> before = ciphertextsByToken();

        VaultRotation.Pass pass = rotation.rotateAll(KeyRing.SHARED_SCOPE, 10);

        assertThat(pass.rewrapped()).isEqualTo(3);
        assertThat(pass.lost()).isZero();

        assertThat(ciphertextsByToken())
                .as("NOT ONE encrypted card may have been rewritten by a key rotation")
                .isEqualTo(before);

        assertThat(vaultOnV2.detokenize(tokens.get(0)).pan()).isEqualTo("4242424242424242");
        assertThat(vaultOnV2.detokenize(tokens.get(1)).pan()).isEqualTo("4111111111111111");
        assertThat(vaultOnV2.detokenize(tokens.get(2)).pan()).isEqualTo("5555555555554444");
    }

    /** After rotating, every row names the new version. */
    @Test
    void everyRowEndsUpOnTheCurrentKeyVersion() {
        tokenizeAll("4242424242424242", "4111111111111111");

        assertThat(rotation.remaining()).containsExactly(Map.entry("shared/v1", 2L));

        rotation.rotateAll(KeyRing.SHARED_SCOPE, 10);

        assertThat(rotation.remaining()).containsExactly(Map.entry("shared/v2", 2L));
    }

    /**
     * NO CUTOVER. The vault is readable at every point during a partial
     * rotation, which is what makes it safe to stop halfway and resume days
     * later.
     */
    @Test
    void theVaultStaysReadableWhileTheRotationIsOnlyPartlyDone() {
        List<String> tokens = tokenizeAll(
                "4242424242424242", "4111111111111111", "5555555555554444");

        // One row at a time, checking everything still reads after each.
        VaultRotation oneAtATime = new VaultRotation(connection.jdbc(),
                new EnvelopeCipher(new KeyRing(new InMemoryKekStore(ring("v1", KEK_V1, "v2", KEK_V2), "v2"))), 1);

        for (int i = 0; i < 3; i++) {
            oneAtATime.rotate(KeyRing.SHARED_SCOPE);
            for (String token : tokens) {
                assertThat(vaultOnV2.detokenize(token).pan())
                        .as("every card must read at every point of a partial rotation")
                        .hasSize(16);
            }
        }

        assertThat(oneAtATime.remaining()).containsExactly(Map.entry("shared/v2", 3L));
    }

    /** Running it again when there is nothing left to do is free and harmless. */
    @Test
    void aSecondRotationOverAFullyRotatedVaultDoesNothing() {
        tokenizeAll("4242424242424242");
        rotation.rotateAll(KeyRing.SHARED_SCOPE, 10);

        VaultRotation.Pass second = rotation.rotate(KeyRing.SHARED_SCOPE);

        assertThat(second.rewrapped()).isZero();
        assertThat(second.complete()).isTrue();
    }

    /**
     * A row written after the ring moved on is already current and is not
     * touched - so a rotation running while traffic continues converges rather
     * than chasing its own tail.
     */
    @Test
    void rowsWrittenAfterTheRingMovedAreAlreadyCurrent() {
        tokenizeAll("4242424242424242");
        vaultOnV2.tokenize("4111111111111111", 12, 2030, KeyRing.SHARED_SCOPE);

        VaultRotation.Pass pass = rotation.rotateAll(KeyRing.SHARED_SCOPE, 10);

        assertThat(pass.rewrapped())
                .as("only the v1 row needed moving")
                .isEqualTo(1);
        assertThat(rotation.remaining()).containsExactly(Map.entry("shared/v2", 2L));
    }

    // --- the legacy population ---------------------------------------------

    /**
     * A pre-9b row has no DEK, so rotation cannot move it - and must say so
     * rather than reporting success over rows it silently skipped.
     *
     * <p>The naive predicate {@code WHERE kek_version <> :current} would miss
     * these entirely: SQL three-valued logic makes it UNKNOWN for a NULL, so
     * every legacy row would be quietly excluded and {@code remaining()} would
     * look clean while those rows sat unreadable-by-the-new-scheme forever.
     */
    @Test
    void preEnvelopeRowsAreCountedRatherThanSilentlySkipped() {
        tokenizeAll("4242424242424242");
        insertLegacyRow("tok_legacy");

        VaultRotation.Pass pass = rotation.rotateAll(KeyRing.SHARED_SCOPE, 10);

        assertThat(pass.rewrapped()).isEqualTo(1);
        assertThat(pass.legacy())
                .as("the row that cannot be rotated must be reported, not hidden")
                .isEqualTo(1);
        assertThat(rotation.remaining())
                .containsEntry("shared/v2", 1L)
                .containsEntry("legacy", 1L);
    }

    /** And a legacy row is still readable, through the phase-1 cipher. */
    @Test
    void aPreEnvelopeRowIsStillReadableThroughTheLegacyCipher() {
        String legacyKey = key();
        PanCipher legacyCipher = new PanCipher(legacyKey);
        String token = "tok_legacy";

        PanCipher.Encrypted encrypted = legacyCipher.encrypt("4242424242424242", token);
        connection.jdbc().sql("""
                        INSERT INTO token_vault
                            (token, pan_iv, pan_cipher, bin, last4, expiry_month, expiry_year)
                        VALUES (:t, :iv, :c, '424242', '4242', 12, 2030)
                        """)
                .param("t", token).param("iv", encrypted.iv()).param("c", encrypted.ciphertext())
                .update();

        TokenVault withLegacy = new TokenVault(connection.jdbc(), legacyCipher,
                new EnvelopeCipher(new KeyRing(new InMemoryKekStore(ring("v2", KEK_V2), "v2"))));

        assertThat(withLegacy.detokenize(token).pan()).isEqualTo("4242424242424242");
    }

    /**
     * A vault with no legacy key configured says so usefully when it meets a
     * legacy row, rather than failing with a decryption error that reads like
     * corruption.
     */
    @Test
    void aLegacyRowWithNoLegacyKeyFailsWithAnActionableMessage() {
        insertLegacyRow("tok_legacy");

        assertThatThrownBy(() -> vaultOnV2.detokenize("tok_legacy"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("predates envelope encryption")
                .hasMessageContaining("no legacy key");
    }

    // --- retiring a key -----------------------------------------------------

    /**
     * Removing a KEK version while rows still name it makes those cards
     * permanently unreadable - which is why {@link VaultRotation#remaining()}
     * exists and must be checked first.
     *
     * <p>The same mechanism, used deliberately, is phase 9c's crypto-shredding.
     */
    @Test
    void retiringAKeyWithRowsStillOnItMakesThemUnreadable() {
        List<String> tokens = tokenizeAll("4242424242424242");

        // v1 destroyed before the rotation reached that row.
        TokenVault afterShred = new TokenVault(connection.jdbc(), null,
                new EnvelopeCipher(new KeyRing(new InMemoryKekStore(ring("v2", KEK_V2), "v2"))));

        assertThatThrownBy(() -> afterShred.detokenize(tokens.get(0)))
                .isInstanceOf(KeyRing.UnknownKeyException.class)
                .hasMessageContaining("v1");

        assertThat(rotation.remaining())
                .as("and remaining() is what would have warned an operator first")
                .containsExactly(Map.entry("shared/v1", 1L));
    }

    // --- phase 9c: crypto-shredding, against a real table ------------------

    /**
     * PHASE 9C'S EXIT CRITERION: an erasure request renders one merchant's
     * historical card data unrecoverable, verified.
     *
     * <p>Two merchants tokenize cards. One is erased. Their cards become
     * permanently unreadable and the other merchant is entirely unaffected -
     * <strong>and not one row is deleted or modified.</strong>
     *
     * <p>That last part is the whole argument. Nothing goes looking for copies,
     * because there is nothing to look for: the ciphertext stays exactly where
     * it is, in the table, in the replica, in last night's backup, and every
     * copy of it is now meaningless. A {@code DELETE} would satisfy the request
     * only until somebody restored a backup.
     */
    @Test
    void erasingOneMerchantMakesTheirCardsUnrecoverableAndTouchesNoRow() {
        InMemoryKekStore store = new InMemoryKekStore(ring("v1", KEK_V1), "v1");
        KeyRing keys = new KeyRing(store);
        TokenVault vault = new TokenVault(connection.jdbc(), null, new EnvelopeCipher(keys));

        String aliceToken = vault.tokenize("4242424242424242", 12, 2030, "merchant-alice").token();
        String bobToken = vault.tokenize("4111111111111111", 12, 2030, "merchant-bob").token();

        long rowsBefore = connection.jdbc().sql("SELECT COUNT(*) FROM token_vault")
                .query(Long.class).single();
        Map<String, String> ciphertextsBefore = ciphertextsByToken();

        assertThat(keys.forget("merchant-alice"))
                .as("one key version destroyed")
                .isEqualTo(1);

        assertThatThrownBy(() -> vault.detokenize(aliceToken))
                .as("the erased merchant's card is unrecoverable")
                .isInstanceOf(KeyRing.UnknownKeyException.class);

        assertThat(vault.detokenize(bobToken).pan())
                .as("and no other merchant was affected")
                .isEqualTo("4111111111111111");

        assertThat(connection.jdbc().sql("SELECT COUNT(*) FROM token_vault")
                .query(Long.class).single())
                .as("erasure deletes nothing - that is what lets it survive a restore")
                .isEqualTo(rowsBefore);
        assertThat(ciphertextsByToken())
                .as("and rewrites nothing either")
                .isEqualTo(ciphertextsBefore);
    }

    /**
     * A restore cannot bring an erased card back.
     *
     * <p>Simulated the only way it can be in-process: the row is copied out and
     * re-inserted after the erasure, which is exactly what restoring a backup of
     * this table would do. It is still unreadable, because the key was never in
     * the table to be restored.
     *
     * <p>This is the assertion that separates crypto-shredding from
     * {@code DELETE FROM token_vault}. The deleted row comes back; the destroyed
     * key does not.
     */
    @Test
    void restoringTheTableDoesNotUndoAnErasure() {
        InMemoryKekStore store = new InMemoryKekStore(ring("v1", KEK_V1), "v1");
        KeyRing keys = new KeyRing(store);
        TokenVault vault = new TokenVault(connection.jdbc(), null, new EnvelopeCipher(keys));

        String token = vault.tokenize("4242424242424242", 12, 2030, "merchant-alice").token();

        // A backup, taken before the erasure.
        connection.jdbc().sql("CREATE TABLE backup AS SELECT * FROM token_vault").update();

        keys.forget("merchant-alice");
        connection.jdbc().sql("DELETE FROM token_vault").update();

        // ...and restored afterwards. Every byte is back.
        connection.jdbc().sql("INSERT INTO token_vault SELECT * FROM backup").update();

        assertThat(connection.jdbc().sql("SELECT COUNT(*) FROM token_vault")
                .query(Long.class).single())
                .as("the row is fully restored")
                .isEqualTo(1);
        assertThatThrownBy(() -> vault.detokenize(token))
                .as("and it is still meaningless, because the key was never in the backup")
                .isInstanceOf(KeyRing.UnknownKeyException.class);
    }

    /**
     * The shared scope is not erasable per-merchant, and a record that lands
     * there by accident is a record that cannot be erased.
     *
     * <p>Asserted so the consequence of choosing the wrong scope is written
     * down: erasing the shared scope destroys every record that used it, which
     * is why it is the default rather than the choice.
     */
    @Test
    void erasingTheSharedScopeTakesEverythingInIt() {
        InMemoryKekStore store = new InMemoryKekStore(ring("v1", KEK_V1), "v1");
        KeyRing keys = new KeyRing(store);
        TokenVault vault = new TokenVault(connection.jdbc(), null, new EnvelopeCipher(keys));

        String first = vault.tokenize("4242424242424242", 12, 2030, KeyRing.SHARED_SCOPE).token();
        String second = vault.tokenize("4111111111111111", 12, 2030, KeyRing.SHARED_SCOPE).token();

        keys.forget(KeyRing.SHARED_SCOPE);

        assertThatThrownBy(() -> vault.detokenize(first))
                .isInstanceOf(KeyRing.UnknownKeyException.class);
        assertThatThrownBy(() -> vault.detokenize(second))
                .as("both, because they shared a key - which is the point of scoping")
                .isInstanceOf(KeyRing.UnknownKeyException.class);
    }

    /** Rotation is per-scope: rotating one merchant leaves the others alone. */
    @Test
    void rotatingOneScopeDoesNotTouchAnother() {
        InMemoryKekStore store = new InMemoryKekStore(ring("v1", KEK_V1), "v1");
        KeyRing keys = new KeyRing(store);
        EnvelopeCipher cipher = new EnvelopeCipher(keys);
        TokenVault vault = new TokenVault(connection.jdbc(), null, cipher);
        VaultRotation scoped = new VaultRotation(connection.jdbc(), cipher, 100);

        String aliceToken = vault.tokenize("4242424242424242", 12, 2030, "merchant-alice").token();
        String bobToken = vault.tokenize("4111111111111111", 12, 2030, "merchant-bob").token();

        keys.rotate("merchant-alice");
        VaultRotation.Pass pass = scoped.rotateAll("merchant-alice", 10);

        assertThat(pass.rewrapped()).isEqualTo(1);
        assertThat(scoped.remaining())
                .containsEntry("merchant-alice/v2", 1L)
                .containsEntry("merchant-bob/v1", 1L);

        assertThat(vault.detokenize(aliceToken).pan()).isEqualTo("4242424242424242");
        assertThat(vault.detokenize(bobToken).pan()).isEqualTo("4111111111111111");
    }

    private void insertLegacyRow(String token) {
        connection.jdbc().sql("""
                        INSERT INTO token_vault
                            (token, pan_iv, pan_cipher, bin, last4, expiry_month, expiry_year)
                        VALUES (:t, X'000000000000000000000000', X'00', '424242', '4242', 12, 2030)
                        """)
                .param("t", token)
                .update();
    }
}
