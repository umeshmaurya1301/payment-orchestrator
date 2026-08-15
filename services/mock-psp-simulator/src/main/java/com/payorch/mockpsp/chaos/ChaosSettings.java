package com.payorch.mockpsp.chaos;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * How badly the simulated provider is behaving right now.
 *
 * <p>Every experiment from phase 2 to phase 10 drives this record. The four
 * knobs are not variations on one theme - each finds a different class of bug,
 * and conflating them is how a chaos harness ends up proving nothing.
 *
 * @param latencyMs     fixed delay before responding. Finds missing deadline
 *                      budgets and connection-pool exhaustion.
 * @param errorRate     probability of a 500. Finds missing retries and is what
 *                      a circuit breaker counts.
 * @param hangRate      probability of <strong>never responding at all</strong>.
 *                      Distinct from latency, and the one that finds missing
 *                      read timeouts - a slow response eventually frees the
 *                      thread, a hang does not.
 * @param duplicateRate probability of processing an authorization the provider
 *                      has already seen, instead of returning the original.
 *                      Finds missing provider-side idempotency, which is the
 *                      failure that ends in a double charge.
 */
public record ChaosSettings(
        @Min(0) @Max(600_000) long latencyMs,
        @DecimalMin("0.0") @DecimalMax("1.0") double errorRate,
        @DecimalMin("0.0") @DecimalMax("1.0") double hangRate,
        @DecimalMin("0.0") @DecimalMax("1.0") double duplicateRate) {

    /** Everything off. The state the simulator starts in and returns to on reset. */
    public static ChaosSettings healthy() {
        return new ChaosSettings(0, 0, 0, 0);
    }

    public boolean isHealthy() {
        return latencyMs == 0 && errorRate == 0 && hangRate == 0 && duplicateRate == 0;
    }
}
