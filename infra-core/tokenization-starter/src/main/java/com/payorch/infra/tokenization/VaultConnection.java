package com.payorch.infra.tokenization;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The vault's connection pool, held behind a type that is deliberately
 * <strong>not</strong> a {@link DataSource}.
 *
 * <p>That is the whole reason this wrapper exists, and it is worth being
 * explicit about. Spring finds databases by type. Registering the vault pool as
 * a {@code DataSource} bean would make it visible to:
 *
 * <ul>
 *   <li>{@code DataSourceAutoConfiguration}, which backs off the moment any
 *       {@code DataSource} bean exists - so adding the vault would silently
 *       remove the application's own datasource;</li>
 *   <li>Flyway, which would happily run the application's migrations into the
 *       vault schema;</li>
 *   <li>JPA, which would map entities onto it;</li>
 *   <li>Actuator's datasource health indicator, which would report the vault's
 *       connectivity on a public health endpoint.</li>
 * </ul>
 *
 * <p>None of those are things anyone would ask for, and all of them happen by
 * default. Hiding the pool behind a wrapper means the vault is reachable only
 * through {@link TokenVault}, by code that asked for it by name.
 */
public final class VaultConnection implements AutoCloseable {

    private final HikariDataSource pool;
    private final JdbcClient jdbc;

    public VaultConnection(HikariDataSource pool) {
        this.pool = pool;
        this.jdbc = JdbcClient.create(pool);
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    @Override
    public void close() {
        pool.close();
    }
}
