package com.payorch.edge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * REST surface, API-key auth, rate limiting, idempotency, and the origin of the deadline budget.
 *
 * <p>The single entry point for a raw card number. It tokenizes on arrival, so
 * everything downstream carries {@code bin + token + last4} and nothing else,
 * and the CVV is discarded here rather than forwarded.
 *
 * <p>Phase 1: {@code POST /v1/payments} and {@code GET /v1/payments/{id}},
 * API-key authentication against a hashed key, and idempotency with
 * byte-identical response replay. No rate limiting and no deadline budget yet -
 * those are phases 3 and 7, after the failures they prevent have been measured.
 */
@SpringBootApplication
public class PaymentsEdgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentsEdgeApplication.class, args);
    }
}