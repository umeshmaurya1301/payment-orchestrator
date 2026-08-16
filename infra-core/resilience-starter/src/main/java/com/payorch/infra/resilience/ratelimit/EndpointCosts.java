package com.payorch.infra.resilience.ratelimit;

/**
 * The endpoint bucket names, in one place.
 *
 * <p>These strings are a join: the autoconfiguration uses them to build the
 * per-endpoint limiters, the service's classifier uses them to say which bucket
 * a request spends from, and the metrics use them as a tag value. A typo in any
 * one of the three produces a bucket that exists but is never charged - the
 * endpoint appears unlimited, no error is raised anywhere, and the only symptom
 * is a limit that does not limit.
 *
 * <p>Constants rather than an enum because the set is open: a service may
 * classify into a bucket this library has never heard of, and
 * {@link EndpointRateLimiter} is deliberately tolerant of that.
 */
public final class EndpointCosts {

    /** {@code POST /v1/payments} - a connection, a vault round trip, a row, a downstream call. */
    public static final String PAYMENTS_WRITE = "payments.write";

    /** {@code GET /v1/payments/{id}} - one indexed read. */
    public static final String PAYMENTS_READ = "payments.read";

    private EndpointCosts() {
    }
}
