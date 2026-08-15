package com.payorch.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Starts the real context against an in-memory database.
 *
 * <p>Flyway is disabled here rather than pointed at H2. The phase-1 migrations
 * are MySQL-specific - {@code ENGINE = InnoDB}, {@code BINARY(16)},
 * {@code DATETIME(3)} - so running them against H2 would either fail or, worse,
 * succeed against a schema that is not the one production uses. They are
 * verified where they actually run: against the MySQL container during
 * {@code docker compose up}, where {@code ddl-auto: validate} fails startup the
 * moment the entities and the migrations disagree.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:orchestrator;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
        })
class PaymentOrchestratorApplicationTest {

    @Test
    void contextLoads() {
    }
}
