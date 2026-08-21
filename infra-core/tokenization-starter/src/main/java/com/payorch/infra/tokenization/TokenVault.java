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
                (token, pan_iv, pan_cipher, wrapped_dek, dek_iv, key_scope, kek_version,
                 bin, last4, expiry_month, expiry_year)
            VALUES (:token, :iv, :cipher, :wrappedDek, :dekIv, :keyScope, :kekVersion,
                    :bin, :last4, :expiryMonth, :expiryYear)
            """;

    private static final String SELECT = """
            SELECT pan_iv, pan_cipher, wrapped_dek, dek_iv, key_scope, kek_version,
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

    /**
     * 9c. Nullable, and only in the sense that a vault used purely for
     * tokenization has nothing to audit yet. Once set, no read bypasses it -
     * which is the reason the log lives inside this class rather than in a
     * decorator a caller could be constructed without.
     */
    private VaultAccessLog auditLog;

    public void setAuditLog(VaultAccessLog auditLog) {
        this.auditLog = auditLog;
    }

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
    /**
     * @param keyScope the erasure boundary - the merchant. Phase 9c. Every card
     *        tokenized under a scope becomes permanently unreadable the moment
     *        that scope's key material is destroyed, in every copy of this table
     *        that exists anywhere, without any of those copies being touched.
     *        Passing {@link KeyRing#SHARED_SCOPE} opts a record OUT of
     *        individual erasure, which is occasionally right and is never the
     *        default anybody should reach for absent-mindedly.
     */
    public TokenizedCard tokenize(String rawPan, int expiryMonth, int expiryYear,
                                  String keyScope) {
        Pan fragments = Pan.of(rawPan);
        String normalised = Pan.normalise(rawPan);
        String token = newToken();

        EnvelopeCipher.Sealed sealed = envelope.encrypt(normalised, token, keyScope);
        jdbc.sql(INSERT)
                .param("token", token)
                .param("iv", sealed.iv())
                .param("cipher", sealed.ciphertext())
                .param("wrappedDek", sealed.wrappedKey().ciphertext())
                .param("dekIv", sealed.wrappedKey().iv())
                .param("keyScope", sealed.wrappedKey().keyScope())
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
        return detokenize(token, VaultAccess.unattributed());
    }

    /**
     * Reads a card, and records that it was read. Phase 9c.
     *
     * <h2>The order matters</h2>
     *
     * <p>The audit row is written <strong>before the card is returned</strong>
     * and after the outcome is known. Writing it first would record reads that
     * never happened; writing it after the return - or in a finally block that
     * swallows - would let a failed audit still disclose a card, which is the
     * one sequence the fail-closed setting exists to prevent.
     *
     * <p>Decryption happens before the audit write, so a PAN briefly exists in
     * memory for a read that is then refused. That is not a disclosure: it is
     * never handed to a caller, and the alternative - auditing before knowing
     * the outcome - would mean the log could not record whether the token was
     * even real.
     *
     * <h2>Failures are audited too, and they are the interesting rows</h2>
     *
     * <p>A successful read by an authorised service is the boring case. A run of
     * {@code UNKNOWN_TOKEN} from one actor is somebody walking the token space,
     * and this is the only place in the system where that would be visible.
     */
    public DetokenizedCard detokenize(String token, VaultAccess access) {
        Optional<DetokenizedCard> row;
        try {
            row = jdbc.sql(SELECT)
                    .param("token", token)
                    .query((rs, n) -> new DetokenizedCard(
                            readPan(rs, token),
                            rs.getInt("expiry_month"),
                            rs.getInt("expiry_year")))
                    .optional();

        } catch (RuntimeException e) {
            // A key that cannot unwrap, a legacy row with no legacy cipher, a
            // vault that is unreachable. Recorded, because "the card could not
            // be read" is exactly as interesting to an investigation as "it
            // was" - a crypto-shred shows up here.
            audit(token, access, VaultAccessLog.FAILED);
            throw e;
        }

        if (row.isEmpty()) {
            audit(token, access, VaultAccessLog.UNKNOWN_TOKEN);
            throw new UnknownTokenException(token);
        }

        audit(token, access, VaultAccessLog.SUCCESS);
        return row.get();
    }

    private void audit(String token, VaultAccess access, String outcome) {
        if (auditLog != null) {
            auditLog.record(token, access, outcome);
        }
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

        // key_scope is NULL for rows written by phase 9b, before scoping
        // existed. They belong to the shared scope by definition - there was
        // only one key - and reading them that way is what lets 9b deployments
        // adopt 9c without a rewrite.
        String keyScope = rs.getString("key_scope");
        return envelope.decrypt(
                new EnvelopeCipher.Sealed(panIv, panCipher,
                        new KeyRing.WrappedKey(
                                keyScope == null ? KeyRing.SHARED_SCOPE : keyScope,
                                kekVersion, rs.getBytes("dek_iv"), rs.getBytes("wrapped_dek"))),
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
