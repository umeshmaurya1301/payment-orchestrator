package com.payorch.infra.tokenization;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the token vault, under {@code payorch.vault}.
 *
 * <p>The datasource settings live here rather than under
 * {@code spring.datasource} because they are a <em>second</em>, separately
 * credentialed connection. {@code payments-edge} supplies a user with
 * {@code SELECT, INSERT}; {@code psp-connector} supplies one with {@code SELECT}
 * only. Nothing else in the system has any grant on the vault schema.
 *
 * @param key              base64 of 32 raw bytes. Phase 1's single static key,
 *                         retained ONLY to read rows written before phase 9b -
 *                         nothing encrypts through it any more. Optional: a
 *                         deployment with no legacy rows does not need it
 * @param keys             the KEK ring, {@code version -> base64 key}. Several
 *                         versions may be present at once, which is what makes
 *                         rotation gradual rather than a cutover
 * @param currentKey       the ring version new records are wrapped under
 * @param verifyOnStartup  whether to prove the vault table is reachable during
 *                         startup rather than on the first payment
 * @param datasource       the vault connection
 */
@ConfigurationProperties(prefix = "payorch.vault")
public record VaultProperties(String key,
                              java.util.Map<String, String> keys,
                              String currentKey,
                              Boolean verifyOnStartup,
                              Datasource datasource,
                              Datasource kekDatasource) {

    public VaultProperties {
        if (keys == null) {
            keys = java.util.Map.of();
        }
        // Boxed, and defaulted here rather than declared as a primitive.
        // Constructor binding gives an absent primitive boolean the value
        // false, so a primitive would make "not configured" mean "skip the
        // check" - the opposite of what a safety check should default to.
        if (verifyOnStartup == null) {
            verifyOnStartup = Boolean.TRUE;
        }
        if (datasource == null) {
            datasource = new Datasource(null, null, null, 5);
        }
        // Phase 9d. Separate from `datasource` on purpose, and not merely for
        // tidiness: KekStore's contract says key material must not share a
        // backup domain with the ciphertext it protects. Different schema,
        // different credentials, so no vault grant can read a KEK and no KEK
        // grant can read a card. On one MySQL instance that is a weaker form of
        // the rule than it deserves - see JdbcKekStore - but it is the half that
        // can be enforced here.
        //
        // Absent means "keep the in-memory store", which is what every unit test
        // and every service that does not touch cards wants.
        if (kekDatasource == null) {
            kekDatasource = new Datasource(null, null, null, 2);
        }
    }

    /**
     * @param poolSize deliberately small. Tokenization is one insert per
     *        payment and detokenization one select; a large pool here would only
     *        buy the ability to hold more connections open against the most
     *        sensitive table in the system.
     */
    public record Datasource(String url, String username, String password, int poolSize) {

        public Datasource {
            if (poolSize <= 0) {
                poolSize = 5;
            }
        }
    }
}
