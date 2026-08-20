package com.payorch.infra.tokenization;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store that had to exist before a payment could cross two processes.
 *
 * <p>{@link InMemoryKekStore} is correct and is tested. What was never tested is
 * the property the live system actually needs: that a key minted by <em>one</em>
 * store is readable by a <em>different</em> store instance. In memory it never
 * can be, and that is why every payment on the live stack failed at
 * detokenization from phase 9b until this class existed - the edge minted the
 * merchant's KEK in its own heap and the connector looked for it in another.
 *
 * <p>So the first test below constructs two stores over one database on purpose.
 * A test that reused a single instance would pass against the broken design,
 * which is exactly how the defect survived to the end of the roadmap.
 */
class JdbcKekStoreTest {

    private static final String SCHEMA = """
            CREATE TABLE kek_material (
                scope        VARCHAR(64)   NOT NULL,
                version      VARCHAR(32)   NOT NULL,
                key_material VARBINARY(64) NOT NULL,
                is_current   BOOLEAN       NOT NULL DEFAULT FALSE,
                created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (scope, version)
            )
            """;

    private static final String SCOPE = "merchant-under-test";
    private static final String SHARED_KEY =
            Base64.getEncoder().encodeToString(new byte[32]);

    private HikariDataSource pool;
    private JdbcClient jdbc;

    @BeforeEach
    void createSchema() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:kek-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        pool = new HikariDataSource(config);
        jdbc = JdbcClient.create(pool);
        jdbc.sql(SCHEMA).update();
    }

    @AfterEach
    void close() {
        pool.close();
    }

    private JdbcKekStore store() {
        return new JdbcKekStore(jdbc, Map.of(), null);
    }

    /**
     * THE REGRESSION, and the reason this class exists. Two store instances -
     * standing in for payments-edge and psp-connector - over one database. The
     * key one mints must be the key the other finds.
     */
    @Test
    void aKeyMintedByOneProcessIsReadableByAnother() {
        JdbcKekStore edge = store();
        JdbcKekStore connector = store();

        String version = edge.createCurrentVersion(SCOPE);

        Optional<byte[]> found = connector.find(SCOPE, version);
        assertThat(found)
                .as("an in-memory store gives each process its own heap, and every "
                        + "detokenization in the other one fails")
                .isPresent();
        assertThat(found.get()).hasSize(32);
        assertThat(edge.find(SCOPE, version).get()).isEqualTo(found.get());
    }

    /** A second process must see the same current version, not mint a rival one. */
    @Test
    void theCurrentVersionIsVisibleAcrossInstances() {
        String version = store().createCurrentVersion(SCOPE);

        assertThat(store().currentVersion(SCOPE)).contains(version);
    }

    /**
     * Rotation keeps the old key. It exists only to unwrap what it already
     * wrapped - dropping it would make every existing card unreadable, which is
     * the thing that makes rotation and "rewrite all the data" the same
     * operation if you get this wrong.
     */
    @Test
    void rotatingAddsAVersionAndKeepsTheOldOneReadable() {
        JdbcKekStore store = store();
        String first = store.createCurrentVersion(SCOPE);
        byte[] firstMaterial = store.find(SCOPE, first).orElseThrow();

        String second = store.createCurrentVersion(SCOPE);

        assertThat(second).isNotEqualTo(first);
        assertThat(store.currentVersion(SCOPE)).contains(second);
        assertThat(store.find(SCOPE, first))
                .as("the old key must survive rotation or every card it wrapped is lost")
                .isPresent();
        assertThat(store.find(SCOPE, first).get()).isEqualTo(firstMaterial);
        assertThat(store.find(SCOPE, second).get()).isNotEqualTo(firstMaterial);
    }

    /**
     * Crypto-shredding, phase 9c. Every version goes, not just the current one -
     * leaving an older version behind leaves the records it wrapped readable and
     * the erasure incomplete.
     */
    @Test
    void forgettingAScopeDestroysEveryVersionOfItsKey() {
        JdbcKekStore store = store();
        String first = store.createCurrentVersion(SCOPE);
        String second = store.createCurrentVersion(SCOPE);

        assertThat(store.forget(SCOPE)).isEqualTo(2);

        assertThat(store.find(SCOPE, first)).isEmpty();
        assertThat(store.find(SCOPE, second)).isEmpty();
        assertThat(store.currentVersion(SCOPE)).isEmpty();
        assertThat(store.scopes()).doesNotContain(SCOPE);
    }

    /** One merchant's erasure must not touch another's. That is the point of scoping. */
    @Test
    void forgettingOneScopeLeavesTheOthersIntact() {
        JdbcKekStore store = store();
        String mine = store.createCurrentVersion(SCOPE);
        String theirs = store.createCurrentVersion("another-merchant");

        store.forget(SCOPE);

        assertThat(store.find("another-merchant", theirs)).isPresent();
        assertThat(store.find(SCOPE, mine)).isEmpty();
    }

    /**
     * The shared scope is seeded from configuration rather than minted, so a 9b
     * deployment's existing keys keep reading the records they wrapped. Seeding
     * runs in every service at startup, so it has to be idempotent.
     */
    @Test
    void theSharedScopeIsSeededFromConfigurationAndSeedingTwiceIsSafe() {
        new JdbcKekStore(jdbc, Map.of("v1", SHARED_KEY), "v1");
        new JdbcKekStore(jdbc, Map.of("v1", SHARED_KEY), "v1");

        JdbcKekStore store = store();
        assertThat(store.find(KeyRing.SHARED_SCOPE, "v1")).isPresent();
        assertThat(store.currentVersion(KeyRing.SHARED_SCOPE)).contains("v1");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM kek_material WHERE scope = :s")
                .param("s", KeyRing.SHARED_SCOPE)
                .query(Integer.class).single())
                .as("a second startup must not duplicate or replace the seeded key")
                .isEqualTo(1);
    }

    /** A scope nobody has used has no key, and that is not an error. */
    @Test
    void anUnknownScopeSimplyHasNoKey() {
        assertThat(store().find("never-seen", "v1")).isEmpty();
        assertThat(store().currentVersion("never-seen")).isEmpty();
        assertThat(store().forget("never-seen")).isZero();
    }
}
