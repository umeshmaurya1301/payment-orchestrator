package com.payorch.connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * All resilience. Provider adapters and per-provider configuration.
 *
 * <p>The only service permitted to detokenize, and only at the last moment before a provider call that needs the PAN. Phase 9c puts RBAC and an access audit log around that.
 *
 * <p>Phase 0: bare skeleton with a health endpoint. No business logic.
 */
@SpringBootApplication
public class PspConnectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PspConnectorApplication.class, args);
    }
}