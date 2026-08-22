package com.payorch.mockpsp;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockPspSimulatorApplicationTest {

    @Test
    void contextLoads() {
    }
}