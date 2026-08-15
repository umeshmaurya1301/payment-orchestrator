package com.payorch.router;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Health-based provider selection.
 *
 * <p>Phase 5 turns this into the differentiator: circuit-breaker state, rolling success rate and rolling P99 become inputs to a routing decision rather than just a fail-fast switch.
 *
 * <p>Phase 0: bare skeleton with a health endpoint. No business logic.
 */
@SpringBootApplication
public class PspRouterApplication {

    public static void main(String[] args) {
        SpringApplication.run(PspRouterApplication.class, args);
    }
}