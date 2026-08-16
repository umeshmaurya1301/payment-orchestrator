package com.payorch.infra.resilience.ratelimit;

import java.util.concurrent.atomic.LongAdder;

/**
 * Admits everything, and counts what it admitted.
 *
 * <p>The "before" arm. Every experiment in phase 3 needs the component removed
 * without the wiring around it changing, and deleting beans to get a control run
 * changes more than the variable - the filter's position in the chain, the
 * header it writes, the cost of the merchant lookup. This keeps all of that and
 * removes only the decision.
 *
 * <p>It still counts, so a control run reports the offered load in the same
 * series as the treated run instead of reporting nothing at all.
 */
public class UnlimitedRateLimiter implements RateLimiter {

    private final LongAdder permitted = new LongAdder();

    @Override
    public Decision tryAcquire(String key, int permits) {
        permitted.increment();
        return Decision.allowed(Long.MAX_VALUE);
    }

    @Override
    public String kind() {
        return "unlimited";
    }

    @Override
    public long permitted() {
        return permitted.sum();
    }

    @Override
    public long rejected() {
        return 0;
    }
}
