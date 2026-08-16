package com.payorch.infra.resilience.ratelimit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The three layers, as one bean.
 *
 * <p>A named holder rather than a {@code Map<String, RateLimiter>} bean, which
 * would have been the obvious shape and is a trap: Spring treats a
 * {@code Map<String, T>} injection point as "give me every bean of type T keyed
 * by bean name", so the map published here would be ignored in favour of one
 * assembled from the container. The failure is silent and produces a map with
 * the wrong keys.
 *
 * <p>Holding all three together is also what keeps them consistent. The layers
 * share an implementation choice ({@code atomic-lua} against
 * {@code read-modify-write}) and an enabled flag, and 3e's experiment depends on
 * flipping those for the whole system at once - a run where two layers are
 * atomic and one is not measures nothing.
 */
public record RateLimiters(RateLimiter merchant, RateLimiter endpoint, RateLimiter egress) {

    /** For metrics, which report per layer. Ordered so the output reads the same every time. */
    public Map<String, RateLimiter> asMap() {
        Map<String, RateLimiter> layers = new LinkedHashMap<>();
        layers.put("merchant", merchant);
        layers.put("endpoint", endpoint);
        layers.put("egress", egress);
        return layers;
    }

    /** True when every layer is admitting unconditionally - the control arm. */
    public boolean disabled() {
        return merchant instanceof UnlimitedRateLimiter
                && endpoint instanceof UnlimitedRateLimiter
                && egress instanceof UnlimitedRateLimiter;
    }
}
