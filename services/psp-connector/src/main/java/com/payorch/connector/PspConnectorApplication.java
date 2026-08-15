package com.payorch.connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * All resilience. Provider adapters and per-provider configuration.
 *
 * <p>The only service permitted to detokenize, and only at the last moment
 * before a provider call that needs the PAN. Its vault credentials are granted
 * {@code SELECT} and nothing else, so the restriction is enforced by the
 * database rather than by convention. Phase 9c adds RBAC and an access audit
 * log on top.
 *
 * <p>Phase 1: one adapter, and <strong>zero resilience annotations</strong> -
 * no retry, no breaker, no bulkhead, not even a timeout. That is the phase-1
 * constraint. Phase 2 saturates this call path deliberately, and phase 3 adds
 * one defence at a time against what that measured. Despite the module
 * description, this service earns its name in phase 3, not here.
 */
@SpringBootApplication
public class PspConnectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PspConnectorApplication.class, args);
    }
}