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

    @Bean
    @ConditionalOnMissingBean
    public PanCipher panCipher(VaultProperties properties) {
        if (properties.key() == null || properties.key().isBlank()) {
            throw new IllegalStateException(
                    "payorch.vault.key is required whenever payorch.vault.datasource.url is set. "
                            + "Generate one with: openssl rand -base64 32");
        }
        return new PanCipher(properties.key());
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenVault tokenVault(VaultConnection connection, PanCipher panCipher) {
        return new TokenVault(connection.jdbc(), panCipher);
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
