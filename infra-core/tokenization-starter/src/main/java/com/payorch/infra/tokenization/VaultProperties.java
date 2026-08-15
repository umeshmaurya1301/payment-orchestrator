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
 * @param key              base64 of 32 raw bytes, the AES-256 key
 * @param verifyOnStartup  whether to prove the vault table is reachable during
 *                         startup rather than on the first payment
 * @param datasource       the vault connection
 */
@ConfigurationProperties(prefix = "payorch.vault")
public record VaultProperties(String key, Boolean verifyOnStartup, Datasource datasource) {

    public VaultProperties {
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
