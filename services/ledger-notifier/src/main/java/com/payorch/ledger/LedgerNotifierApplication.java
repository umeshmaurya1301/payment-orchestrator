package com.payorch.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Kafka consumers into Mongo. Double-entry ledger, outbound webhooks, reconciliation.
 *
 * <p>Runs under the sync compose profile from phase 6 onward.
 *
 * <p>Phase 0: bare skeleton with a health endpoint. No business logic.
 */
@SpringBootApplication
public class LedgerNotifierApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerNotifierApplication.class, args);
    }
}