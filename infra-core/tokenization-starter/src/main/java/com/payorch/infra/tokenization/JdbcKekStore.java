package com.payorch.infra.tokenization;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * KEKs in a database, so that more than one process can read them.
 *
 * <h2>Why this had to exist before anything could be measured</h2>
 *
 * <p>{@link InMemoryKekStore} is honest about being development-only, and its
 * javadoc says the cost is that "every merchant-scoped key is destroyed on
 * restart". Across two processes the cost is larger than that and was not
 * written down: <strong>the key is never visible to the other process at
 * all.</strong>
 *
 * <p>The live path is two services. {@code payments-edge} tokenizes a card and
 * calls {@link KekStore#createCurrentVersion} for the merchant's scope, which
 * mints a KEK <em>in the edge's heap</em>. {@code psp-connector} later
 * detokenizes the same card and looks that scope up in <em>its own</em> heap,
 * which has never heard of it. Every payment failed:
 *
 * <pre>
 *   KeyRing$UnknownKeyException: no key material for scope
 *   '0192abcd-0000-7000-8000-000000000001' version 'v1' - it was never
 *   created, or it has been erased
 * </pre>
 *
 * <p>Phase 9b's envelope encryption and 9c's per-merchant scoping were both
 * unit-tested and both correct in isolation. What was untested was two JVMs, and
 * the defect survived from 9b to the end of the roadmap because the stack was
 * never started again after it was introduced.
 *
 * <h2>The requirement this only partly meets</h2>
 *
 * <p>{@link KekStore} states the one rule any implementation has to obey: key
 * material must not share a backup domain with the ciphertext it protects, or
 * destroying the key erases nothing. A separate schema with its own credentials
 * on the same MySQL instance is <strong>a weaker form of that</strong> - it
 * separates the grant domain, so no vault credential can read a KEK and no KEK
 * credential can read a card, but one {@code mysqldump} of the instance still
 * captures both.
 *
 * <p>That is a real limitation and it is the reason this class does not claim to
 * close the gap. Vault or a KMS remains the production answer. What this does
 * buy is the thing the roadmap actually needed: keys that two processes agree
 * on, and a {@link #forget} that is a real delete rather than a process
 * restart.
 *
 * <h2>Crypto-shredding still works</h2>
 *
 * <p>{@link #forget} deletes the rows. Phase 9c's argument is unchanged and is
 * in fact stronger here than it was in memory: the card ciphertext stays exactly
 * where it is, in the vault, and becomes permanently undecryptable. Deleting the
 * mapping row would not have been erasure, because a restore brings it back;
 * destroying the key makes every copy of the ciphertext - including the ones in
 * last night's backup, which nobody can find - unreadable.
 */
public class JdbcKekStore implements KekStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcKekStore.class);

    /** AES-256. The same length {@link InMemoryKekStore} validates its configured keys against. */
    private static final int KEY_BYTES = 32;

    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    public JdbcKekStore(JdbcClient jdbc, Map<String, String> sharedKeys, String sharedCurrent) {
        this.jdbc = jdbc;
        seedSharedScope(sharedKeys, sharedCurrent);
    }

    /**
     * The shared scope comes from configuration, not from this table.
     *
     * <p>It is seeded rather than minted so a 9b deployment's existing
     * {@code payorch.vault.keys} keep working unchanged - the records already
     * wrapped under them have to stay readable, and a freshly generated key
     * would not read them. Idempotent, because every service that takes this
     * starter runs it at startup and they all race on the first boot.
     */
    private void seedSharedScope(Map<String, String> sharedKeys, String sharedCurrent) {
        if (sharedKeys == null || sharedKeys.isEmpty()) {
            return;
        }
        sharedKeys.forEach((version, base64) -> {
            byte[] material = decode(version, base64);
            try {
                // A plain INSERT and a caught duplicate, NOT "ON DUPLICATE KEY
                // UPDATE". That clause is MySQL-only; H2 rejects it outright,
                // so the portable form is also the one the tests can exercise -
                // and the catch below was already needed for the startup race.
                jdbc.sql("""
                        INSERT INTO kek_material (scope, version, key_material, is_current)
                        VALUES (:scope, :version, :material, :current)
                        """)
                        .param("scope", KeyRing.SHARED_SCOPE)
                        .param("version", version)
                        .param("material", material)
                        .param("current", version.equals(sharedCurrent))
                        .update();
            } catch (DuplicateKeyException alreadySeeded) {
                // Another instance won the race. The material is identical -
                // it comes from the same configuration - so there is nothing
                // to reconcile.
                log.debug("shared KEK version {} was already seeded", version);
            }
        });
    }

    private static byte[] decode(String version, String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("KEK version '" + version + "' is not valid base64", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("KEK version '" + version
                    + "' must decode to 32 bytes for AES-256, got " + raw.length);
        }
        return raw;
    }

    @Override
    public Optional<byte[]> find(String scope, String version) {
        return jdbc.sql("SELECT key_material FROM kek_material WHERE scope = :scope AND version = :version")
                .param("scope", scope)
                .param("version", version)
                .query(byte[].class)
                .optional();
    }

    /**
     * Mints the next version for a scope and makes it current.
     *
     * <p>Two processes can reach this at the same moment for a merchant's first
     * payment. Both compute the same next version, one inserts and the other
     * gets a duplicate key - at which point the loser must <strong>use the
     * winner's key, not retry with its own</strong>. Generating a second key for
     * the same (scope, version) would leave two processes each able to wrap under
     * a key the other cannot unwrap, which is precisely the bug this class was
     * written to fix, reintroduced one level down.
     */
    @Override
    public String createCurrentVersion(String scope) {
        for (int attempt = 1; attempt <= 5; attempt++) {
            int next = jdbc.sql("SELECT COUNT(*) FROM kek_material WHERE scope = :scope")
                    .param("scope", scope)
                    .query(Integer.class)
                    .single() + 1;
            String version = "v" + next;

            byte[] material = new byte[KEY_BYTES];
            random.nextBytes(material);

            try {
                jdbc.sql("""
                        INSERT INTO kek_material (scope, version, key_material, is_current)
                        VALUES (:scope, :version, :material, TRUE)
                        """)
                        .param("scope", scope)
                        .param("version", version)
                        .param("material", material)
                        .update();
            } catch (DuplicateKeyException lostTheRace) {
                // Somebody else minted this version. Take theirs.
                Optional<String> current = currentVersion(scope);
                if (current.isPresent()) {
                    log.debug("lost the race minting {} for scope, using existing {}",
                            version, current.get());
                    return current.get();
                }
                continue;
            }

            // Demote the others only after the new one is safely in. Doing it
            // first would leave a window with no current version, and a
            // concurrent tokenize would mint a third.
            jdbc.sql("UPDATE kek_material SET is_current = FALSE "
                            + "WHERE scope = :scope AND version <> :version")
                    .param("scope", scope)
                    .param("version", version)
                    .update();
            return version;
        }
        throw new IllegalStateException(
                "could not establish a current KEK version for the scope after 5 attempts");
    }

    @Override
    public Optional<String> currentVersion(String scope) {
        return jdbc.sql("SELECT version FROM kek_material WHERE scope = :scope AND is_current = TRUE")
                .param("scope", scope)
                .query(String.class)
                .optional();
    }

    /**
     * Crypto-shredding. Returns how many versions were destroyed.
     *
     * <p>Every version, not just the current one - a scope's older versions are
     * kept precisely because they can still unwrap records, so leaving them
     * behind would leave those records readable and the erasure incomplete.
     */
    @Override
    public int forget(String scope) {
        int destroyed = jdbc.sql("DELETE FROM kek_material WHERE scope = :scope")
                .param("scope", scope)
                .update();
        if (destroyed > 0) {
            // WARN, and without the scope in a structured field. The scope IS a
            // merchant id, and this line is the audit trail for an irreversible
            // act, so it says how many and leaves who to the audit log.
            log.warn("crypto-shredded {} KEK version(s) - the cards wrapped under them "
                    + "are now permanently undecryptable", destroyed);
        }
        return destroyed;
    }

    @Override
    public Set<String> scopes() {
        return new LinkedHashSet<>(
                jdbc.sql("SELECT DISTINCT scope FROM kek_material ORDER BY scope")
                        .query(String.class)
                        .list());
    }
}
