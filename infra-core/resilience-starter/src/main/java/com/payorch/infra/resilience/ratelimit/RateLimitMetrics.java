package com.payorch.infra.resilience.ratelimit;

import java.util.Map;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Publishes each layer's admissions and rejections, tagged by layer and by
 * implementation.
 *
 * <p>The {@code layer} tag is what keeps the three limiters from being reported
 * as one number. "Rate limited" is not an outcome an operator can act on:
 * {@code merchant} means one caller is over their allowance, {@code endpoint}
 * means the service is at capacity, and {@code egress} means we are about to
 * breach a provider's contract. Three different pages, three different fixes.
 *
 * <p>{@code store.failures} exists because of what fail-open costs in
 * observability. When Redis is unreachable
 * {@link RedisTokenBucketRateLimiter} admits everything, and from a success
 * rate that is indistinguishable from a limiter working perfectly under light
 * load. This is the series that tells them apart, and it is the one to alert on:
 * a limiter that has quietly stopped limiting is worse than one that is loudly
 * refusing, because nothing about the system's behaviour says so until the load
 * arrives.
 */
public class RateLimitMetrics implements MeterBinder {

    private final Map<String, RateLimiter> limitersByLayer;

    public RateLimitMetrics(Map<String, RateLimiter> limitersByLayer) {
        this.limitersByLayer = limitersByLayer;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        limitersByLayer.forEach((layer, limiter) -> {
            Tags tags = Tags.of("layer", layer, "kind", limiter.kind());

            Gauge.builder("payorch.ratelimit.permitted", limiter, RateLimiter::permitted)
                    .description("Requests admitted by this layer")
                    .tags(tags)
                    .register(registry);

            Gauge.builder("payorch.ratelimit.rejected", limiter, RateLimiter::rejected)
                    .description("Requests refused with 429 - load shed at the door")
                    .tags(tags)
                    .register(registry);

            if (limiter instanceof RedisTokenBucketRateLimiter redis) {
                Gauge.builder("payorch.ratelimit.store.failures", redis,
                                RedisTokenBucketRateLimiter::storeFailures)
                        .description("Requests admitted because Redis could not answer - "
                                + "the limiter is failing open and is not limiting anything")
                        .tags(tags)
                        .register(registry);
            }
        });
    }
}
