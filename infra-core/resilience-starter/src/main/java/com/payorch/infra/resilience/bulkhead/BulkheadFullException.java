package com.payorch.infra.resilience.bulkhead;

/**
 * No permit became available before the wait ran out.
 *
 * <p>Like an open circuit breaker, this means <strong>nothing was sent</strong>.
 * The provider was never contacted, so the payment is {@code FAILED} and safely
 * retryable - not {@code UNKNOWN}. That is the third mechanism in phase 3 to
 * produce a definite non-event, and they all report it the same way for the same
 * reason: a caller that cannot tell "we did not try" from "we do not know" will
 * eventually retry into a double charge.
 */
public class BulkheadFullException extends RuntimeException {

    private final String key;
    private final long waitedMs;

    public BulkheadFullException(String key, long waitedMs) {
        super("no bulkhead permit for '" + key + "' after " + waitedMs + "ms; nothing was sent");
        this.key = key;
        this.waitedMs = waitedMs;
    }

    public String key() {
        return key;
    }

    public long waitedMs() {
        return waitedMs;
    }
}
