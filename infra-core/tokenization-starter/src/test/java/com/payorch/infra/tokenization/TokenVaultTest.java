package com.payorch.infra.tokenization;

import java.util.Base64;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenVaultTest {

    /**
     * One merchant's erasure boundary. Phase 9c made this a required argument:
     * a card has to be encrypted under a scope from the moment it is first
     * stored, because records wrapped under a shared key cannot be separated
     * afterwards without decrypting and re-encrypting them.
     */
    private static final String SCOPE = "merchant-under-test";

    private static final String SCHEMA = """
            CREATE TABLE token_vault (
                token        VARCHAR(48)   NOT NULL PRIMARY KEY,
                pan_iv       VARBINARY(12) NOT NULL,
                pan_cipher   VARBINARY(64) NOT NULL,
                -- Phase 9b. The envelope. Nullable, because a row without a
                -- kek_version is a pre-9b record read through the legacy cipher.
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

    private VaultConnection connection;
    private TokenVault vault;

    @BeforeEach
    void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:vault-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        connection = new VaultConnection(new HikariDataSource(config));
        connection.jdbc().sql(SCHEMA).update();

        String key = Base64.getEncoder().encodeToString(new byte[32]);
        // Both ciphers, because this vault has to read both formats: envelope
        // for anything it writes, and the phase-1 direct-key form for rows that
        // predate 9b.
        vault = new TokenVault(connection.jdbc(),
                new PanCipher(key),
                new EnvelopeCipher(new KeyRing(new InMemoryKekStore(java.util.Map.of("v1", key), "v1"))));
    }

    @AfterEach
    void tearDown() {
        connection.close();
    }

    @Test
    void tokenizeThenDetokenizeReturnsTheOriginalCard() {
        TokenizedCard card = vault.tokenize("4242 4242 4242 4242", 12, 2030, SCOPE);

        assertThat(card.token()).startsWith("tok_");
        assertThat(card.bin()).isEqualTo("424242");
        assertThat(card.last4()).isEqualTo("4242");

        DetokenizedCard reversed = vault.detokenize(card.token());

        assertThat(reversed.pan()).isEqualTo("4242424242424242");
        assertThat(reversed.expiryMonth()).isEqualTo(12);
        assertThat(reversed.expiryYear()).isEqualTo(2030);
    }

    /**
     * The record's generated {@code toString} would print the card number. That
     * default is the easiest possible PAN leak - one careless {@code log.debug}
     * away - so it is overridden, and the override is pinned here.
     */
    @Test
    void detokenizedCardDoesNotPrintItself() {
        TokenizedCard card = vault.tokenize("4242424242424242", 12, 2030, SCOPE);

        assertThat(vault.detokenize(card.token()).toString()).doesNotContain("4242");
    }

    /**
     * The claim the whole tokenization boundary rests on: the stored row is not
     * a card number in any encoding an operator or a backup would see.
     */
    @Test
    void storedRowHoldsNoReadableCardNumber() {
        TokenizedCard card = vault.tokenize("4242424242424242", 12, 2030, SCOPE);

        String stored = connection.jdbc()
                .sql("SELECT CAST(pan_cipher AS VARCHAR) FROM token_vault WHERE token = :t")
                .param("t", card.token())
                .query(String.class)
                .single();

        assertThat(stored).doesNotContain("4242424242424242");
    }

    @Test
    void everyTokenizationProducesADistinctToken() {
        TokenizedCard first = vault.tokenize("4242424242424242", 12, 2030, SCOPE);
        TokenizedCard second = vault.tokenize("4242424242424242", 12, 2030, SCOPE);

        assertThat(first.token()).isNotEqualTo(second.token());
        assertThat(vault.detokenize(first.token()).pan()).isEqualTo("4242424242424242");
        assertThat(vault.detokenize(second.token()).pan()).isEqualTo("4242424242424242");
    }

    @Test
    void unknownTokenIsADistinctException() {
        assertThatThrownBy(() -> vault.detokenize("tok_nonexistent"))
                .isInstanceOf(TokenVault.UnknownTokenException.class);
    }

    @Test
    void refusesToTokenizeSomethingThatIsNotACard() {
        assertThatThrownBy(() -> vault.tokenize("1234", 12, 2030, SCOPE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyReachableSucceedsAgainstAPresentTable() {
        vault.verifyReachable();
    }

    @Test
    void verifyReachableExplainsItselfWhenTheTableIsMissing() {
        connection.jdbc().sql("DROP TABLE token_vault").update();

        assertThatThrownBy(vault::verifyReachable)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("docker compose down -v");
    }
}
