package com.payorch.infra.resilience.bulkhead;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Bounds how many calls to one provider are in flight at once.
 *
 * <p>This is the component the phase-2 baseline asked for first. Everything
 * before it bounded how <em>long</em> a request lives - the deadline in 3a, the
 * retry budget in 3b, the breaker in 3c. None of them bound how <em>many</em>
 * exist. At 200 rps with a 30 s budget the system still carries 6,000 in-flight
 * requests, which is the arithmetic that exhausted the heap and killed
 * {@code payments-edge} five times.
 *
 * <p><strong>A bulkhead that queues is not admission control.</strong> If a call
 * waits indefinitely for a permit, the queue has simply moved from the provider
 * into this service's heap, which is exactly where the baseline's failure lived.
 * So the wait is bounded, and bounded by the request's own remaining budget
 * rather than by a separate number - see {@link BulkheadFullException}.
 *
 * <p>Two implementations, because phase 3d's stated deliverable is measuring
 * them against each other under virtual threads rather than assuming.
 */
public interface Bulkhead {

    /**
     * @param key  the provider, so one provider saturating cannot starve another
     * @throws BulkheadFullException if no permit became available in time.
     *         Nothing was sent, so the caller may treat it as a definite
     *         non-event - the same contract an open circuit breaker has.
     */
    <T> T call(String key, Callable<T> work) throws Exception;

    /** {@code semaphore} or {@code threadpool}. Reported in metrics and at startup. */
    String kind();

    /** Remaining capacity per key, for metrics. */
    Map<String, Integer> available();

    long permitted();

    long rejected();
}
