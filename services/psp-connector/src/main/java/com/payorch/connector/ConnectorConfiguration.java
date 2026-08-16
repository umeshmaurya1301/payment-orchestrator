package com.payorch.connector;

import javax.sql.DataSource;

import com.payorch.connector.config.ProviderConfigEndpoint;
import com.payorch.connector.config.ProviderConfigMetrics;
import com.payorch.connector.config.ProviderConfigStore;
import com.payorch.connector.provider.MockPspAdapter;
import com.payorch.connector.provider.PspAdapter;
import com.payorch.connector.provider.PspAdapterRegistry;
import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import com.payorch.infra.resilience.breaker.CircuitBreakers;
import com.payorch.infra.resilience.bulkhead.Bulkhead;
import com.payorch.infra.resilience.ratelimit.RateLimiters;
import com.payorch.infra.resilience.retry.Retrier;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the connector's providers, which from phase 3f are database rows rather
 * than beans.
 *
 * <p>Every adapter is created on demand by {@link PspAdapterRegistry} from a
 * {@code psp_config} row, so adding a provider is an {@code INSERT} and changing
 * one is an {@code UPDATE}. Nothing here names a provider except the factory
 * that builds one.
 */
@Configuration
@EnableScheduling
public class ConnectorConfiguration {

    /**
     * A second read-only connection, for one table.
     *
     * <p>Separate credentials from the vault's, not shared, and the reason is the
     * same one that produced two vault users in phase 1: "read only" is not one
     * permission. {@code config_reader} holds {@code SELECT} on
     * {@code payorch.psp_config} and nothing else, so this connection cannot read
     * a payment, a merchant or a card - and {@code vault_reader}, which can read
     * cards, cannot read configuration. Two narrow credentials rather than one
     * convenient one.
     *
     * <p>Two connections in the pool. This is a poll of a handful of rows every
     * two seconds, and a pool sized for load here would just be memory that
     * exists so a configuration read can be fast in parallel with itself.
     */
    @Bean
    public DataSource configDataSource(
            @Value("${payorch.psp.config-datasource.url}") String url,
            @Value("${payorch.psp.config-datasource.username}") String username,
            @Value("${payorch.psp.config-datasource.password}") String password) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setPoolName("psp-config-pool");
        dataSource.setMaximumPoolSize(2);
        // Belt as well as braces. The grant already makes writes impossible;
        // this makes an accidental write fail in the driver with a clear
        // message rather than as an access-denied from MySQL that reads like a
        // credentials problem.
        dataSource.setReadOnly(true);
        return dataSource;
    }

    /**
     * Loaded synchronously at startup, then polled.
     *
     * <p>{@code initMethod} rather than a {@code @PostConstruct} inside the store
     * so the failure is a startup failure: a connector that cannot read its
     * providers has nothing to connect to, and coming up healthy on defaults
     * nobody chose is worse than not coming up.
     */
    @Bean(initMethod = "loadOrFail")
    public ProviderConfigStore providerConfigStore(DataSource configDataSource,
                                                   CircuitBreakers breakers,
                                                   Bulkhead bulkhead,
                                                   RateLimiters rateLimiters) {
        return new ProviderConfigStore(
                new JdbcTemplate(configDataSource), breakers, bulkhead,
                // 3e's egress layer. Only this one of the three is wired here -
                // psp-connector has no merchants and no public surface, so an
                // ingress limiter would be a bucket nothing ever spends from.
                rateLimiters.egress());
    }

    @Bean
    public ProviderConfigEndpoint providerConfigEndpoint(ProviderConfigStore store) {
        return new ProviderConfigEndpoint(store);
    }

    @Bean
    public ProviderConfigMetrics providerConfigMetrics(ProviderConfigStore store) {
        return new ProviderConfigMetrics(store);
    }

    @Bean
    public PspAdapterRegistry pspAdapterRegistry(ProviderConfigStore configs,
                                                 java.util.List<PspAdapter> declaredAdapters,
                                                 DeadlinePropagation propagation,
                                                 DeadlineExecutor deadlines,
                                                 Retrier retrier,
                                                 CircuitBreakers breakers,
                                                 Bulkhead bulkhead,
                                                 RateLimiters rateLimiters) {
        return new PspAdapterRegistry(configs, declaredAdapters, pspId -> adapterFor(
                pspId, configs, propagation, deadlines, retrier, breakers, bulkhead, rateLimiters));
    }

    /**
     * Every provider in this system speaks the {@code mock-psp-simulator}
     * protocol, so there is one adapter class and the factory is a constructor
     * call. A real second protocol is a phase-5 concern; the seam is here, and it
     * is a {@code switch} on {@code pspId} when that day comes rather than a
     * redesign of how providers are resolved.
     */
    private static PspAdapter adapterFor(String pspId,
                                         ProviderConfigStore configs,
                                         DeadlinePropagation propagation,
                                         DeadlineExecutor deadlines,
                                         Retrier retrier,
                                         CircuitBreakers breakers,
                                         Bulkhead bulkhead,
                                         RateLimiters rateLimiters) {
        return new MockPspAdapter(pspId, configs, propagation, deadlines, retrier,
                breakers, bulkhead, rateLimiters.egress());
    }
}
