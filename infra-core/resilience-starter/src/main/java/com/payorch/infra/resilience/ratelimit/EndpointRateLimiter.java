package com.payorch.infra.resilience.ratelimit;

import java.util.Map;

/**
 * Routes each endpoint bucket to its own limiter, so the endpoints get genuinely
 * <em>different limits</em> rather than different prices against a shared pool.
 *
 * <p>The distinction is not pedantic. Pricing a status read at one token and an
 * authorisation at three, out of one bucket, means a burst of polling can still
 * consume the entire capacity available to payments - the endpoints compete, and
 * the cheap high-volume one wins. Separate buckets mean a flood of status reads
 * degrades status reads and nothing else, which is the containment the layer is
 * for.
 *
 * <p>Sizing them apart is the point:
 *
 * <ul>
 *   <li>{@code POST /v1/payments} takes a connection, a vault round trip, an
 *       idempotency row and a downstream call. Its ceiling comes from what
 *       {@code payments-edge} survives - 3d's 500 rps arm produced
 *       {@code OutOfMemoryError} twice.</li>
 *   <li>{@code GET /v1/payments/{id}} is one indexed read. Holding it to the
 *       write limit would throttle merchants for polling the status of payments
 *       we already accepted, which is both cheap to serve and something we
 *       actively want them to do instead of retrying the write.</li>
 * </ul>
 */
public class EndpointRateLimiter implements RateLimiter {

    private final Map<String, RateLimiter> byBucket;
    private final RateLimiter fallback;

    public EndpointRateLimiter(Map<String, RateLimiter> byBucket, RateLimiter fallback) {
        this.byBucket = Map.copyOf(byBucket);
        this.fallback = fallback;
    }

    @Override
    public Decision tryAcquire(String bucket, int permits) {
        // An unrecognised bucket falls back rather than throwing. A new endpoint
        // added without a limit should be unlimited-but-counted, not a 500 - the
        // limiter must never be the reason a working endpoint stops working.
        return byBucket.getOrDefault(bucket, fallback).tryAcquire(bucket, permits);
    }

    @Override
    public String kind() {
        return byBucket.values().stream().findFirst().orElse(fallback).kind();
    }

    @Override
    public long permitted() {
        return byBucket.values().stream().mapToLong(RateLimiter::permitted).sum()
                + fallback.permitted();
    }

    @Override
    public long rejected() {
        return byBucket.values().stream().mapToLong(RateLimiter::rejected).sum()
                + fallback.rejected();
    }

    /** The per-bucket limiters, so metrics can report each endpoint separately. */
    public Map<String, RateLimiter> byBucket() {
        return byBucket;
    }
}
