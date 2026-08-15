package com.payorch.infra.resilience;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Placeholder so the module, its autoconfiguration registration and its
 * substitution into the services are all proven working before phase 3 has
 * anything to put here.
 *
 * <p>Phase 3 populates this in strict order, one component at a time, with a
 * load test and a written result after each: deadline budget (3a), retry with
 * classification and budget (3b), per-provider-per-operation circuit breaker
 * (3c), bulkhead (3d), the three rate-limiter layers (3e), then dynamic config
 * reload from {@code psp_config} (3f).
 */
@AutoConfiguration
public class ResilienceAutoConfiguration {
}
