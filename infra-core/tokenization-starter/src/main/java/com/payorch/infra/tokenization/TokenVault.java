package com.payorch.infra.tokenization;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The token vault. The only code in the system that can turn a token back into
 * a card number.
 *
 * <p>Both directions live in one class on purpose. "Raw PAN exists in exactly
 * three places" is only a defensible claim if reversal happens on one code
 * path that can be pointed at, reviewed and audited. Scattering
 * decrypt-and-use across adapters is how that claim quietly stops being true.
 *
 * <p><strong>Access is enforced by database credentials, not by this class.</strong>
 * {@code payments-edge} connects with a user granted {@code SELECT, INSERT};
 * {@code psp-connector} connects with a user granted {@code SELECT} only; the
 * ordinary application user has no grant on the vault schema at all. A bug
 * that calls {@link #tokenize} from the connector therefore fails at the
 * database, not at a code review - which is the difference between a control
 * and a convention.
 */
public class TokenVault {

    /**
     * 16 bytes of {@link SecureRandom}, base64url without padding, giving a
     * 22-character token.
     *
     * <p>This is the one identifier in the system that is deliberately
     * <em>not</em> a UUIDv7. Everywhere else, time-ordering buys clustered-index
     * locality. Here it would leak creation time and make neighbouring rows
     * guessable from one observed token - and unpredictability is worth more
     * than insert locality on a table whose access is already credential-gated.
     */
    private static final int TOKEN_ENTROPY_BYTES = 16;

    private static final String PREFIX = "tok_";

    private static final String INSERT = """
            INSERT INTO token_vault
                (token, pan_iv, pan_cipher, wrapped_dek, dek_iv, kek_version,
                 bin, last4, expiry_month, expiry_year)
            VALUES (:token, :iv, :cipher, :wrappedDek, :dekIv, :kekVersion,
                    :bin, :last4, :expiryMonth, :expiryYear)
            """;

    private static final String SELECT = """
            SELECT pan_iv, pan_cipher, wrapped_dek, dek_iv, kek_version,
                   expiry_month, expiry_year
            FROM token_vault WHERE token = :token
            """;

    private final JdbcClient jdbc;

    /**
     * Phase 1's direct-key cipher, kept for ONE reason: reading rows written
     * before phase 9b.
     *
     * <p>Those rows were encrypted under a single static key with no DEK, and
     * converting them means decrypting and re-encrypting every one - the
     * expensive migration envelope encryption exists to avoid, which it cannot
     * avoid for the migration INTO itself. So they are read where they lie, and
     * the population only shrinks.
     *
     * <p>Nothing writes through this any more. If it is ever null the vault
     * simply cannot read legacy rows, which is the correct behaviour for a
     * deployment that has none.
     */
    private final PanCipher legacyCipher;

    private final EnvelopeCipher envelope;
    private final SecureRandom random = new SecureRandom();

    public TokenVault(JdbcClient jdbc, PanCipher legacyCipher, EnvelopeCipher envelope) {
        this.jdbc = jdbc;
        this.legacyCipher = legacyCipher;
        this.envelope = envelope;
    }

    /**
     * Swaps a raw card number for a token. Called exactly once per payment, at
     * the edge, before anything else touches the request.
     *
     * @throws IllegalArgumentException if the card number is not plausible.
     *         Never includes the input in its message.
     */
    public TokenizedCard tokenize(String rawPan, int expiryMonth, int expiryYear) {
        Pan fragments = Pan.of(rawPan);
        String normalised = Pan.normalise(rawPan);
        String token = newToken();

        EnvelopeCipher.Sealed sealed = envelope.encrypt(normalised, token);
        jdbc.sql(INSERT)
                .param("token", token)
                .param("iv", sealed.iv())
                .param("cipher", sealed.ciphertext())
                .param("wrappedDek", sealed.wrappedKey().ciphertext())
                .param("dekIv", sealed.wrappedKey().iv())
                .param("kekVersion", sealed.wrappedKey().kekVersion())
                .param("bin", fragments.bin())
                .param("last4", fragments.last4())
                .param("expiryMonth", expiryMonth)
                .param("expiryYear", expiryYear)
                .update();

        return new TokenizedCard(token, fragments.bin(), fragments.last4());
    }

    /**
     * Reverses a token. The only call site is {@code psp-connector}, immediately
     * before the provider call, and the result must not be stored, logged or
     * returned.
     *
     * <p>The expiry comes back with the card because the provider needs both and
     * neither is allowed to travel with the payment. That is what keeps
     * "downstream carries bin + token + last4, nothing more" literally true.
     *
     * @throws UnknownTokenException if no such token exists. A distinct type so
     *         a caller can answer 404 rather than 500 without string-matching.
     */
    public DetokenizedCard detokenize(String token) {
        Optional<DetokenizedCard> row = jdbc.sql(SELECT)
                .param("token", token)
                .query((rs, n) -> new DetokenizedCard(
                        readPan(rs, token),
                        rs.getInt("expiry_month"),
                        rs.getInt("expiry_year")))
                .optional();

        return row.orElseThrow(() -> new UnknownTokenException(token));
    }

    /**
     * Reads a PAN in whichever form its row was written.
     *
     * <p>{@code kek_version} is the discriminator, and it is the column that
     * exists rather than a flag that was added: a row with a KEK version has a
     * wrapped DEK by construction, and a row without one predates the scheme. No
     * separate "format" column, which would be a second source of truth about
     * the same fact.
     */
    private String readPan(java.sql.ResultSet rs, String token) throws java.sql.SQLException {
        String kekVersion = rs.getString("kek_version");
        byte[] panIv = rs.getBytes("pan_iv");
        byte[] panCipher = rs.getBytes("pan_cipher");

        if (kekVersion == null) {
            // A row from before phase 9b: encrypted directly under the static
            // key, no DEK to unwrap.
            if (legacyCipher == null) {
                throw new IllegalStateException(
                        "token " + token + " predates envelope encryption and no legacy key "
                                + "is configured to read it");
            }
            return legacyCipher.decrypt(new PanCipher.Encrypted(panIv, panCipher), token);
        }

        return envelope.decrypt(
                new EnvelopeCipher.Sealed(panIv, panCipher,
                        new KeyRing.WrappedKey(kekVersion, rs.getBytes("dek_iv"),
                                rs.getBytes("wrapped_dek"))),
                token);
    }

    /**
     * Proves the configured credentials can actually reach the vault table.
     *
     * <p>Called at startup. Without it, a stale MySQL data volume - one created
     * before the vault init script existed - produces a service that starts
     * healthy and fails on the first payment with a SQL error nobody reads as
     * "your database predates this feature".
     *
     * @throws IllegalStateException with an actionable message
     */
    public void verifyReachable() {
        try {
            jdbc.sql("SELECT COUNT(*) FROM token_vault").query(Long.class).single();
        } catch (DataAccessException e) {
            throw new IllegalStateException(
                    "the token_vault table is not reachable with the configured vault credentials. "
                            + "It is created by docker/mysql/init, which MySQL only runs on a fresh data "
                            + "directory - a volume created before that script existed will not have it. "
                            + "Run `docker compose down -v` and start again.", e);
        }
    }

    /** Thrown when a token has no vault row. */
    public static class UnknownTokenException extends RuntimeException {

        public UnknownTokenException(String token) {
            // The token is safe to include: it is an opaque reference and
            // carries no card data on its own.
            super("no vault entry for token " + token);
        }
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_ENTROPY_BYTES];
        random.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
