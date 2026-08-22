package com.payorch.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Starts the real context on a real port.
 *
 * <p>Cheap, and it proves more than it looks like it does: that both
 * infra-core starters (org.infra, resolved from Maven Local) were discovered
 * through their AutoConfiguration.imports, and that the correlation filter and
 * problem-detail advice registered.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // H2 rather than MySQL, and Flyway off: this test is about
                // whether the context WIRES, not about the schema, and phase 6e
                // gave this service a database it did not have in phase 0.
                "spring.datasource.url=jdbc:h2:mem:ledger;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.flyway.enabled=false",
                // No broker here. The Mongo driver connects lazily, so it needs
                // no equivalent - a MongoTemplate with nobody listening is built
                // happily and only fails on first use.
                "payorch.ledger.listener-autostart=false",
        // No Mongo in this test, and MongoIndexes talks to it at startup.
        "payorch.recon.create-indexes=false",
                "payorch.ledger.bootstrap-servers=localhost:9092"
        })
class LedgerNotifierApplicationTest {

    @Test
    void contextLoads() {
    }
}