package com.payorch.infra.tokenization;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Moves a scope's records onto a new KEK, in batches, without decrypting a card.
 * Phase 9b, scoped in 9c.
 *
 * <h2>What makes this cheap</h2>
 *
 * <p>It reads four columns - {@code token}, {@code wrapped_dek}, {@code dek_iv},
 * {@code key_scope}, {@code kek_version} - and writes three back. It never
 * selects {@code pan_cipher}, so no card ciphertext is loaded, no card plaintext
 * exists at any moment, and no PAN is rewritten. The signature of
 * {@link EnvelopeCipher#rewrap} enforces that rather than merely encouraging it:
 * there is no parameter through which a PAN could reach it.
 *
 * <p>The comparison worth holding onto: rotating a directly-applied key means
 * decrypting and re-encrypting every card in the vault - a long, stateful job
 * holding plaintext PANs in memory and touching the most sensitive table in the
 * system. This moves 48 bytes per row.
 *
 * <h2>One scope at a time, since 9c</h2>
 *
 * <p>Each scope has its own current version, so "everything not on the current
 * key" is not one predicate - it is one per scope. Rotating per scope is also
 * the operationally honest shape: a KEK is rotated because somebody decided to,
 * usually for one merchant after a suspected exposure, and a job that swept
 * every scope at once would be a job nobody could reason about the blast radius
 * of.
 *
 * <h2>Why it is manual, like the ledger repair and unlike the sweeper</h2>
 *
 * <p>Phase 7c's idempotency sweeper is scheduled because expiry is not a symptom
 * of anything. Phase 6j's balance repair is manual because a repair that runs by
 * itself hides the bug that made it necessary. Rotation is the second kind: the
 * decision belongs with the person who added the new key version, and a job that
 * rotated on a timer would also be a job that could quietly fail on one.
 */
public class VaultRotation {

    private static final Logger log = LoggerFactory.getLogger(VaultRotation.class);

    /**
     * Rows in one scope not yet on that scope's current KEK, oldest first.
     *
     * <p>{@code kek_version IS NULL} is included, and it is the case the obvious
     * {@code WHERE kek_version <> :current} misses: SQL three-valued logic makes
     * that predicate UNKNOWN for a NULL, so every pre-9b row would be silently
     * skipped by a job reporting success. Those rows cannot be re-wrapped - they
     * have no DEK - and the point of selecting them is to COUNT them, so
     * "remaining" is honest about what will never move.
     *
     * <p>{@code key_scope IS NULL} is treated as the shared scope: rows written
     * by 9b, before scoping existed, when there was only one key.
     */
    private static final String SELECT_STALE = """
            SELECT token, key_scope, kek_version, dek_iv, wrapped_dek
            FROM token_vault
            WHERE COALESCE(key_scope, :sharedScope) = :scope
              AND (kek_version IS NULL OR kek_version <> :current)
            ORDER BY created_at
            LIMIT :batchSize
            """;

    private static final String REWRAP = """
            UPDATE token_vault
               SET wrapped_dek = :wrappedDek,
                   dek_iv      = :dekIv,
                   key_scope   = :keyScope,
                   kek_version = :kekVersion
             WHERE token = :token
               AND kek_version = :expectedVersion
            """;

    private final JdbcClient jdbc;
    private final EnvelopeCipher envelope;
    private final int batchSize;

    public VaultRotation(JdbcClient jdbc, EnvelopeCipher envelope, int batchSize) {
        this.jdbc = jdbc;
        this.envelope = envelope;
        this.batchSize = batchSize;
    }

    /**
     * @param rewrapped rows moved onto the current KEK
     * @param legacy    rows predating envelope encryption, which cannot be
     *                  re-wrapped. <strong>These never reach zero by
     *                  rotating.</strong> Converting them means decrypting and
     *                  re-encrypting, the expensive migration this scheme avoids
     *                  everywhere except on the way into itself
     * @param lost      rows whose wrapping changed underneath this pass.
     *                  Expected to be zero; see {@link #rotate}
     */
    public record Pass(int rewrapped, int legacy, int lost) {

        /** Whether another pass would find anything left to do. */
        public boolean complete() {
            return rewrapped == 0;
        }
    }

    /**
     * Re-wraps one batch within one scope.
     *
     * <h2>The UPDATE is conditional, and that is not belt-and-braces</h2>
     *
     * <p>{@code AND kek_version = :expectedVersion} means a row whose wrapping
     * changed between the SELECT and the UPDATE is skipped rather than
     * overwritten. Two rotation passes running at once - a second operator, a
     * retried request - could otherwise write a DEK wrapped under the version
     * they read while another pass had already moved it, leaving the stored
     * {@code kek_version} disagreeing with the key that actually wrapped the
     * bytes. That row would be permanently unreadable, and nothing would say so
     * until somebody tried to charge that card.
     *
     * <p>The row count is the decision, exactly as in phase 7c's claim takeover.
     */
    public Pass rotate(String scope) {
        String current = envelope.currentKeyVersion(scope)
                .orElseThrow(() -> new IllegalStateException(
                        "scope '" + scope + "' has no current key version to rotate onto. "
                                + "Call rotate() on the key ring first."));

        List<Row> stale = jdbc.sql(SELECT_STALE)
                .param("sharedScope", KeyRing.SHARED_SCOPE)
                .param("scope", scope)
                .param("current", current)
                .param("batchSize", batchSize)
                .query((rs, n) -> new Row(
                        rs.getString("token"),
                        rs.getString("key_scope"),
                        rs.getString("kek_version"),
                        rs.getBytes("dek_iv"),
                        rs.getBytes("wrapped_dek")))
                .list();

        int rewrapped = 0;
        int legacy = 0;
        int lost = 0;

        for (Row row : stale) {
            if (row.kekVersion() == null) {
                legacy++;
                continue;
            }

            KeyRing.WrappedKey moved = envelope.rewrap(new KeyRing.WrappedKey(
                    row.keyScope() == null ? KeyRing.SHARED_SCOPE : row.keyScope(),
                    row.kekVersion(), row.dekIv(), row.wrappedDek()));

            int updated = jdbc.sql(REWRAP)
                    .param("wrappedDek", moved.ciphertext())
                    .param("dekIv", moved.iv())
                    .param("keyScope", moved.keyScope())
                    .param("kekVersion", moved.kekVersion())
                    .param("token", row.token())
                    .param("expectedVersion", row.kekVersion())
                    .update();

            if (updated == 1) {
                rewrapped++;
            } else {
                lost++;
            }
        }

        if (rewrapped > 0 || lost > 0) {
            log.info("vault rotation pass on scope '{}': {} re-wrapped onto {}, "
                            + "{} legacy rows skipped, {} lost a race",
                    scope, rewrapped, current, legacy, lost);
        }
        return new Pass(rewrapped, legacy, lost);
    }

    /**
     * Runs passes over one scope until nothing is left to move.
     *
     * @param maxPasses a bound, so a bug that never converges stops rather than
     *                  looping against the vault forever
     */
    public Pass rotateAll(String scope, int maxPasses) {
        int rewrapped = 0;
        int legacy = 0;
        int lost = 0;

        for (int pass = 0; pass < maxPasses; pass++) {
            Pass result = rotate(scope);
            rewrapped += result.rewrapped();
            legacy = result.legacy();
            lost += result.lost();
            if (result.complete()) {
                break;
            }
        }
        return new Pass(rewrapped, legacy, lost);
    }

    /**
     * How many rows sit under each scope and version.
     *
     * <p>The number to check before removing a version. A version with rows
     * still on it is a version whose removal makes those cards permanently
     * unreadable - a mistake during a rotation, and the intended outcome during
     * an erasure.
     *
     * @return {@code "scope/version"} to count, with {@code "legacy"} for pre-9b
     *         rows
     */
    public Map<String, Long> remaining() {
        return jdbc.sql("""
                        SELECT COALESCE(key_scope, :sharedScope) AS s,
                               COALESCE(kek_version, 'legacy')   AS v,
                               COUNT(*) AS c
                        FROM token_vault
                        GROUP BY COALESCE(key_scope, :sharedScope),
                                 COALESCE(kek_version, 'legacy')
                        """)
                .param("sharedScope", KeyRing.SHARED_SCOPE)
                .query((rs, n) -> Map.entry(
                        "legacy".equals(rs.getString("v"))
                                ? "legacy"
                                : rs.getString("s") + "/" + rs.getString("v"),
                        rs.getLong("c")))
                .list().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, Long::sum));
    }

    private record Row(String token, String keyScope, String kekVersion,
                       byte[] dekIv, byte[] wrappedDek) {
    }
}
