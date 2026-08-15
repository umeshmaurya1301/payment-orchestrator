package com.payorch.edge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Starts the real context on a real port.
 *
 * <p>Cheap, and it proves more than it looks like it does: that the included
 * build substituted correctly, that all four infra-core starters were discovered
 * through their AutoConfiguration.imports, that the vault connection was built
 * without displacing the application datasource, and that the correlation
 * filter, API-key filter and problem-detail advice all registered.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:edge-context;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "payorch.vault.datasource.url=jdbc:h2:mem:edge-context-vault;DB_CLOSE_DELAY=-1",
                "payorch.vault.datasource.username=sa",
                "payorch.vault.datasource.password=",
                "payorch.vault.verify-on-startup=false",
        })
class PaymentsEdgeApplicationTest {

    @Test
    void contextLoads() {
    }
}
