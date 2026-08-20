package com.payorch.infra.tokenization;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires the token vault into the two services that are allowed to reach it.
 *
 * <p>Gated on {@code payorch.vault.datasource.url} being set, so the starter can
 * sit on a classpath without opening a vault connection. Only
 * {@code payments-edge} and {@code psp-connector} configure it.
 */
@AutoConfiguration
@ConditionalOnClass({JdbcClient.class, HikariDataSource.class})
@ConditionalOnProperty(prefix = "payorch.vault.datasource", name = "url")
@EnableConfigurationProperties(VaultProperties.class)
public class TokenizationAutoConfiguration {

    /**
     * {@code destroyMethod} is left to {@link AutoCloseable}, which Spring calls
     * by convention - the pool must close on shutdown or a container restart
     * leaves connections held against the vault schema.
     */
    @Bean
    @ConditionalOnMissingBean
    public VaultConnection vaultConnection(VaultProperties properties) {
        VaultProperties.Datasource ds = properties.datasource();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(ds.url());
        config.setUsername(ds.username());
        config.setPassword(ds.password());
        config.setMaximumPoolSize(ds.poolSize());
        // Named so it is distinguishable from the application pool in metrics
        // and in a thread dump. "Which pool is exhausted" is a question phase 2
        // asks repeatedly.
        config.setPoolName("vault-pool");
        // The vault holds card data. A pool that hands out a connection with an
        // open transaction from a previous borrower is a correctness problem
        // here in a way it is not elsewhere.
        config.setAutoCommit(true);

        return new VaultConnection(new HikariDataSource(config));
    }

    /**
     * Phase 1's direct-key cipher, now the LEGACY READER and nothing else.
     *
     * <p>No longer required, and that is the change: a deployment created after
     * phase 9b has no rows encrypted this way, so {@code payorch.vault.key} may
     * be absent. Returning null rather than throwing lets that deployment start
     * - and {@link TokenVault} produces an actionable message if a legacy row
     * does turn up without a key to read it.
     */
    @Bean
    @ConditionalOnMissingBean
    public PanCipher panCipher(VaultProperties properties) {
        if (properties.key() == null || properties.key().isBlank()) {
            return null;
        }
        return new PanCipher(properties.key());
    }

    /**
     * The KEK ring. Phase 9b.
     *
     * <p>Falls back to the phase-1 static key as a single version named
     * {@code v1} when no ring is configured. That is a deliberate migration
     * affordance rather than a shortcut: an existing deployment gains envelope
     * encryption for every NEW record without touching its configuration, and
     * gains a rotation story the moment somebody adds a second version.
     *
     * <p>Note what it does not do - it does not re-encrypt anything. Old rows
     * stay directly encrypted and are read by the legacy path; new rows are
     * enveloped under {@code v1}. The two coexist, distinguished by
     * {@code kek_version}.
     */
    /**
     * Where KEKs live. Phase 9c.
     *
     * <p>In memory, which is honest for local development and useless for
     * anything else: every merchant-scoped key is destroyed on restart, so cards
     * tokenized under one become unreadable when the process stops. The shared
     * scope is the exception - it is seeded from configuration and survives,
     * which is what lets a 9b deployment adopt scoping gradually.
     *
     * <p>The production implementation is Vault, and it is outstanding. See
     * {@link KekStore} for the one requirement any implementation has to meet:
     * key material must not share a backup domain with the ciphertext it
     * protects, or destroying it erases nothing.
     */
    @Bean
    @ConditionalOnMissingBean
    public KekStore kekStore(VaultProperties properties) {
        // Phase 9d. A SHARED store when one is configured, and this is not a
        // preference - it is the difference between a payment working and not.
        //
        // The live path spans two JVMs: payments-edge mints a merchant-scoped
        // KEK when it tokenizes, and psp-connector needs that same key to
        // detokenize. An in-memory store gives them one heap each, so the
        // connector looks up a scope it has never heard of and every payment
        // fails with UnknownKeyException. 9b and 9c were both unit-tested and
        // both correct in one process; nothing exercised two until the stack
        // was started again at the end of the roadmap.
        if (properties.kekDatasource().url() != null
                && !properties.kekDatasource().url().isBlank()) {
            VaultProperties.Datasource ds = properties.kekDatasource();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(ds.url());
            config.setUsername(ds.username());
            config.setPassword(ds.password());
            config.setMaximumPoolSize(ds.poolSize());
            config.setPoolName("kek-pool");
            config.setAutoCommit(true);
            return new JdbcKekStore(JdbcClient.create(new HikariDataSource(config)),
                    properties.keys().isEmpty() && properties.key() != null
                            ? java.util.Map.of("v1", properties.key())
                            : properties.keys(),
                    properties.keys().isEmpty() ? "v1" : properties.currentKey());
        }

        if (!properties.keys().isEmpty()) {
            String current = properties.currentKey();
            if (current == null || current.isBlank()) {
                throw new IllegalStateException(
                        "payorch.vault.current-key must name one of the configured "
                                + "payorch.vault.keys versions: " + properties.keys().keySet());
            }
            return new InMemoryKekStore(properties.keys(), current);
        }

        if (properties.key() == null || properties.key().isBlank()) {
            throw new IllegalStateException(
                    "the token vault needs a key. Configure payorch.vault.keys with at least "
                            + "one version and payorch.vault.current-key to name it. "
                            + "Generate a key with: openssl rand -base64 32");
        }
        // A single unversioned key is the phase-1 and 9b form, adopted as the
        // shared scope's v1 so an existing deployment needs no config change.
        return new InMemoryKekStore(java.util.Map.of("v1", properties.key()), "v1");
    }

    @Bean
    @ConditionalOnMissingBean
    public KeyRing keyRing(KekStore kekStore) {
        return new KeyRing(kekStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public EnvelopeCipher envelopeCipher(KeyRing keyRing) {
        return new EnvelopeCipher(keyRing);
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenVault tokenVault(VaultConnection connection,
                                 org.springframework.beans.factory.ObjectProvider<PanCipher> legacy,
                                 EnvelopeCipher envelopeCipher) {
        // ObjectProvider because panCipher may legitimately be absent now.
        return new TokenVault(connection.jdbc(), legacy.getIfAvailable(), envelopeCipher);
    }

    /**
     * Fails startup, loudly and with instructions, if the vault table is not
     * reachable with the configured credentials.
     *
     * <p>An {@code InitializingBean} rather than an event listener: this has to
     * run before the service reports itself healthy, not after.
     */
    @Bean
    @ConditionalOnProperty(prefix = "payorch.vault", name = "verify-on-startup",
            havingValue = "true", matchIfMissing = true)
    public InitializingBean vaultStartupCheck(TokenVault tokenVault) {
        return tokenVault::verifyReachable;
    }
}
