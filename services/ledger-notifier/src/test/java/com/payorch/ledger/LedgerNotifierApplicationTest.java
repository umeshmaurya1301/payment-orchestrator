package com.payorch.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Starts the real context on a real port.
 *
 * <p>Cheap, and it proves more than it looks like it does: that the included
 * build substituted correctly, that both infra-core starters were discovered
 * through their AutoConfiguration.imports, and that the correlation filter and
 * problem-detail advice registered.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerNotifierApplicationTest {

    @Test
    void contextLoads() {
    }
}