package com.payorch.mockpsp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The chaos source. Injects latency, errors, hangs and duplicate responses.
 *
 * <p>Business-level faults live here. Toxiproxy breaks the link, Pumba breaks
 * the process, Chaos Monkey breaks the bean - this breaks the provider.
 *
 * <p>Phase 1 builds it properly and makes it runtime-reconfigurable through
 * {@code POST /_chaos}, with no restart, because every experiment from phase 2
 * to phase 10 drives it and a restart would reset the connection pools and
 * circuit breakers being measured.
 */
@SpringBootApplication
public class MockPspSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockPspSimulatorApplication.class, args);
    }
}