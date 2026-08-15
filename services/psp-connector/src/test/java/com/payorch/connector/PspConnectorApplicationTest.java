package com.payorch.connector;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Starts the real context on a real port.
 *
 * <p>The vault points at H2 and the startup reachability check is off, because
 * this test is about wiring, not about the vault: it proves the included build
 * substituted correctly, that the tokenization starter's autoconfiguration
 * produced a {@code TokenVault}, and that the adapter registry found the
 * configured provider.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "payorch.vault.datasource.url=jdbc:h2:mem:connector-context;DB_CLOSE_DELAY=-1",
                "payorch.vault.datasource.username=sa",
                "payorch.vault.datasource.password=",
                "payorch.vault.verify-on-startup=false",
        })
class PspConnectorApplicationTest {

    @Test
    void contextLoads() {
    }
}
