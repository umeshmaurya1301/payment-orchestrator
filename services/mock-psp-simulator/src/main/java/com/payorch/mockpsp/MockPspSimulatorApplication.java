package com.payorch.mockpsp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The chaos source. Injects latency, errors, hangs and duplicate responses.
 *
 * <p>Business-level faults live here. Toxiproxy breaks the link, Pumba breaks the process, Chaos Monkey breaks the bean - this breaks the provider.
 *
 * <p>Phase 0: bare skeleton with a health endpoint. No business logic.
 */
@SpringBootApplication
public class MockPspSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockPspSimulatorApplication.class, args);
    }
}