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

    private static final String SCHEMA = """
            CREATE TABLE token_vault (
                token        VARCHAR(48)   NOT NULL PRIMARY KEY,
                pan_iv       VARBINARY(12) NOT NULL,
                pan_cipher   VARBINARY(64) NOT NULL,
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

        vault = new TokenVault(connection.jdbc(),
                new PanCipher(Base64.getEncoder().encodeToString(new byte[32])));
    }

    @AfterEach
    void tearDown() {
        connection.close();
    }

    @Test
    void tokenizeThenDetokenizeReturnsTheOriginalCard() {
        TokenizedCard card = vault.tokenize("4242 4242 4242 4242", 12, 2030);

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
        TokenizedCard card = vault.tokenize("4242424242424242", 12, 2030);

        assertThat(vault.detokenize(card.token()).toString()).doesNotContain("4242");
    }

    /**
     * The claim the whole tokenization boundary rests on: the stored row is not
     * a card number in any encoding an operator or a backup would see.
     */
    @Test
    void storedRowHoldsNoReadableCardNumber() {
        TokenizedCard card = vault.tokenize("4242424242424242", 12, 2030);

        String stored = connection.jdbc()
                .sql("SELECT CAST(pan_cipher AS VARCHAR) FROM token_vault WHERE token = :t")
                .param("t", card.token())
                .query(String.class)
                .single();

        assertThat(stored).doesNotContain("4242424242424242");
    }

    @Test
    void everyTokenizationProducesADistinctToken() {
        TokenizedCard first = vault.tokenize("4242424242424242", 12, 2030);
        TokenizedCard second = vault.tokenize("4242424242424242", 12, 2030);

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
        assertThatThrownBy(() -> vault.tokenize("1234", 12, 2030))
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
