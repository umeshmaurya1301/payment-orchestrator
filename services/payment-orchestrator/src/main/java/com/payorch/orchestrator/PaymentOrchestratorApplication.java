package com.payorch.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Payment state machine, MySQL, transactional outbox, saga coordination.
 *
 * <p>Owns every state transition. Phase 1 models the machine as a plain enum
 * with an explicit transition table - no library - covering
 * {@code INITIATED -> ROUTED -> AUTHORIZING -> AUTHORIZED -> CAPTURED -> SETTLED},
 * plus {@code FAILED} and, critically, {@code UNKNOWN}.
 *
 * <p>{@code UNKNOWN} is modelled from the start rather than retrofitted. A
 * connector timeout does not mean the payment failed - it means the outcome is
 * not yet known, and a background poller resolves it into a terminal state
 * later. Systems that conflate "timed out" with "failed" double-charge people.
 *
 * <p>Phase 1: the machine is implemented and drives one payment end to end over
 * REST, with routing decided by {@code psp_config.priority}. No outbox, no saga
 * and no resilience - the outbox and saga are phase 6, and every defence waits
 * until phase 2 has measured the failure it prevents.
 */
@SpringBootApplication
public class PaymentOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentOrchestratorApplication.class, args);
    }
}
