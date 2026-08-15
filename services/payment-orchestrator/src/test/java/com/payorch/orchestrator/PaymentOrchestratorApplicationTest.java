package com.payorch.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Starts the real context against an in-memory database.
 *
 * <p>Flyway is disabled here rather than pointed at H2. The baseline migration
 * would run fine, but phase 1's migrations are MySQL-specific, so a test that
 * ran them against H2 would start failing the moment real DDL landed. The
 * migrations are verified where they actually run - against the MySQL container
 * during {@code docker compose up}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:orchestrator;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.flyway.enabled=false",
        })
class PaymentOrchestratorApplicationTest {

    @Test
    void contextLoads() {
    }
}
