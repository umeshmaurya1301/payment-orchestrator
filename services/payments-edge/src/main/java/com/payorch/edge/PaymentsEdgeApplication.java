package com.payorch.edge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * REST surface, API-key auth, rate limiting, idempotency, and the origin of the deadline budget.
 *
 * <p>The only component that ever sees a raw PAN. From phase 1 it tokenizes on arrival, so everything downstream carries bin + token + last4 and nothing else.
 *
 * <p>Phase 0: bare skeleton with a health endpoint. No business logic.
 */
@SpringBootApplication
public class PaymentsEdgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentsEdgeApplication.class, args);
    }
}