package com.payorch.infra.resilience.ratelimit;

/**
 * The egress limiter refused: sending this call would breach the rate we have
 * agreed with the provider.
 *
 * <p><strong>Nothing was sent.</strong> Same contract as
 * {@link com.payorch.infra.resilience.bulkhead.BulkheadFullException} and an open
 * circuit breaker, and for the same reason - the caller may record a definite
 * {@code FAILED} rather than an {@code UNKNOWN}, because no card was touched.
 *
 * <p>Two things must <em>not</em> happen when this is thrown, and both are easy
 * to get wrong by omission:
 *
 * <ul>
 *   <li><strong>It must not be retried.</strong> Retrying a self-imposed limit
 *       inside the same request spends the deadline arguing with our own
 *       arithmetic. {@code FailureClassifier} maps it to {@code NONE}.</li>
 *   <li><strong>It must not count as a provider fault.</strong> The provider is
 *       healthy; we declined to call it. Counting our own throttling toward the
 *       breaker's failure rate would open a circuit against a provider that has
 *       done nothing wrong, and then keep it open - the breaker would be
 *       reacting to its own side of the conversation. {@code ProviderFault}
 *       returns false for it.</li>
 * </ul>
 */
public class RateLimitedException extends RuntimeException {

    private final String key;
    private final long retryAfterMs;

    public RateLimitedException(String key, long retryAfterMs) {
        super("egress rate limit reached for '" + key + "'; retry after " + retryAfterMs + "ms");
        this.key = key;
        this.retryAfterMs = retryAfterMs;
    }

    public String key() {
        return key;
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }
}
