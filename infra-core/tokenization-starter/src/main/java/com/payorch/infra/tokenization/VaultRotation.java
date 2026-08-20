package com.payorch.infra.tokenization;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Moves the vault onto a new KEK, in batches, without decrypting a card.
 * Phase 9b.
 *
 * <h2>What makes this cheap</h2>
 *
 * <p>It reads three columns - {@code token}, {@code wrapped_dek},
 * {@code dek_iv}, {@code kek_version} - and writes three back. It never selects
 * {@code pan_cipher}, so no card ciphertext is loaded, no card plaintext exists
 * at any moment, and no PAN is rewritten. The signature of
 * {@link EnvelopeCipher#rewrap} enforces that rather than merely encouraging it:
 * there is no parameter through which a PAN could reach it.
 *
 * <p>The comparison worth holding onto: rotating a directly-applied key means
 * decrypting and re-encrypting every card in the vault, which is a long,
 * stateful, resumable job holding plaintext PANs in memory and touching the most
 * sensitive table in the system. This moves 48 bytes per row.
 *
 * <h2>Why it is manual, like the ledger repair and unlike the sweeper</h2>
 *
 * <p>Phase 7c's idempotency sweeper is scheduled because expiry is not a symptom
 * of anything - a record reaching its retention window is the design working.
 * Phase 6j's balance repair is manual because a repair that runs by itself hides
 * the bug that made it necessary.
 *
 * <p>Rotation is the second kind. A KEK is rotated because somebody decided to -
 * on a schedule, after a suspected exposure, or to prove the mechanism works -
 * and the decision belongs with the person who added the new key version to the
 * ring. A job that rotated on a timer would also be a job that could quietly
 * fail on a timer.
 *
 * <h2>No cutover</h2>
 *
 * <p>This can be run repeatedly, stopped halfway, and resumed days later. A row
 * that has not been reached yet still reads, because its wrapped DEK names the
 * old version and the old version is still on the ring. That is the property
 * that makes the whole operation boring, which is the highest praise available
 * for a key rotation.
 *
 * <p>The old version may be removed from the ring only once
 * {@link #remaining()} reports zero for it. Removing it earlier makes those rows
 * permanently unreadable - which is a failure here and, in phase 9c, the
 * deliberate mechanism of crypto-shredding.
 */
public class VaultRotation {

    private static final Logger log = LoggerFactory.getLogger(VaultRotation.class);

    /**
     * Rows not yet on the current KEK, oldest first.
     *
     * <p>{@code kek_version IS NULL} is included, and it is the case that would
     * be missed by the obvious {@code WHERE kek_version <> :current}: SQL's
     * three-valued logic makes that predicate UNKNOWN for a NULL, so every
     * pre-9b row would be silently skipped by a job reporting success. Those
     * rows cannot be re-wrapped - they have no DEK - and the point of selecting
     * them is to COUNT them, so "remaining" is honest about what will never
     * move.
     */
    private static final String SELECT_STALE = """
            SELECT token, wrapped_dek, dek_iv, kek_version
            FROM token_vault
            WHERE kek_version IS NULL OR kek_version <> :current
            ORDER BY created_at
            LIMIT :batchSize
            """;

    private static final String REWRAP = """
            UPDATE token_vault
               SET wrapped_dek = :wrappedDek,
                   dek_iv      = :dekIv,
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
     * What one pass did.
     *
     * @param rewrapped rows moved onto the current KEK
     * @param legacy    rows that predate envelope encryption and cannot be
     *                  re-wrapped. <strong>These never reach zero by rotating.</strong>
     *                  Converting them means decrypting and re-encrypting, which
     *                  is the expensive migration this scheme avoids everywhere
     *                  except on the way into itself
     * @param lost      rows whose wrapping changed underneath this pass. Expected
     *                  to be zero; see {@link #rotate()}
     */
    public record Pass(int rewrapped, int legacy, int lost) {

        /** Whether another pass would find anything left to do. */
        public boolean complete() {
            return rewrapped == 0;
        }
    }

    /**
     * Re-wraps one batch.
     *
     * <h2>The UPDATE is conditional, and that is not belt-and-braces</h2>
     *
     * <p>{@code AND kek_version = :expectedVersion} means a row whose wrapping
     * changed between the SELECT and the UPDATE is skipped rather than
     * overwritten. Two rotation passes running at once - a second operator, a
     * retried request - would otherwise be able to write a DEK wrapped under the
     * version they read while another pass had already moved it, leaving the
     * stored {@code kek_version} disagreeing with the key that actually wrapped
     * the bytes. That row would then be permanently unreadable, and nothing
     * would say so until somebody tried to charge that card.
     *
     * <p>The row count is the decision, exactly as in phase 7c's claim takeover.
     */
    public Pass rotate() {
        List<Row> stale = jdbc.sql(SELECT_STALE)
                .param("current", envelope.currentKeyVersion())
                .param("batchSize", batchSize)
                .query((rs, n) -> new Row(
                        rs.getString("token"),
                        rs.getString("kek_version"),
                        rs.getBytes("dek_iv"),
                        rs.getBytes("wrapped_dek")))
                .list();

        int rewrapped = 0;
        int legacy = 0;
        int lost = 0;

        for (Row row : stale) {
            if (row.kekVersion() == null) {
                // Pre-9b. No DEK to re-wrap; counted so the operator can see
                // that "remaining" will not reach zero and why.
                legacy++;
                continue;
            }

            KeyRing.WrappedKey moved = envelope.rewrap(
                    new KeyRing.WrappedKey(row.kekVersion(), row.dekIv(), row.wrappedDek()));

            int updated = jdbc.sql(REWRAP)
                    .param("wrappedDek", moved.ciphertext())
                    .param("dekIv", moved.iv())
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
            // The token is safe to log - it is an opaque reference. The counts
            // are the point: an operator running this needs to see it converge.
            log.info("vault rotation pass: {} re-wrapped onto {}, {} legacy rows skipped, {} lost a race",
                    rewrapped, envelope.currentKeyVersion(), legacy, lost);
        }
        return new Pass(rewrapped, legacy, lost);
    }

    /**
     * Runs passes until nothing is left to move.
     *
     * @param maxPasses a bound, so a bug that never converges stops rather than
     *                  looping against the vault forever
     */
    public Pass rotateAll(int maxPasses) {
        int rewrapped = 0;
        int legacy = 0;
        int lost = 0;

        for (int pass = 0; pass < maxPasses; pass++) {
            Pass result = rotate();
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
     * How many rows sit under each KEK version.
     *
     * <p>The number to check before removing a version from the ring. A version
     * with rows still on it is a version whose removal makes those cards
     * permanently unreadable.
     *
     * @return version to count, with the key {@code "legacy"} for pre-9b rows
     */
    public Map<String, Long> remaining() {
        return jdbc.sql("""
                        SELECT COALESCE(kek_version, 'legacy') AS v, COUNT(*) AS c
                        FROM token_vault GROUP BY COALESCE(kek_version, 'legacy')
                        """)
                .query((rs, n) -> Map.entry(rs.getString("v"), rs.getLong("c")))
                .list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private record Row(String token, String kekVersion, byte[] dekIv, byte[] wrappedDek) {
    }
}
