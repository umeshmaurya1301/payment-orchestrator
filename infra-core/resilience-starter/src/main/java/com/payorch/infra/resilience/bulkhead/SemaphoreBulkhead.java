package com.payorch.infra.resilience.bulkhead;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import com.payorch.infra.resilience.deadline.Deadline;
import com.payorch.infra.resilience.deadline.Deadlines;

/**
 * Permits, and the caller waits for one on its own thread.
 *
 * <p><strong>The right default under virtual threads.</strong> A caller waiting
 * here parks a virtual thread, which costs a few hundred bytes of heap and no
 * platform thread at all. There is no handoff, no second thread, no queue object
 * - the work runs on the thread that asked for it, so stack traces stay intact
 * and anything thread-bound (the ScopedValue carrying the deadline, MDC) is
 * simply still there.
 *
 * <p>The wait is bounded, and bounded by the <em>request's own remaining
 * budget</em> rather than a separate timeout. That matters: a fixed 500 ms wait
 * would be too long for a request with 200 ms left and needlessly short for one
 * with 20 s. Deriving it from the deadline means the two components cannot
 * disagree, and a request never waits for a permit it could not use.
 */
public class SemaphoreBulkhead implements Bulkhead {

    private final int maxConcurrentCalls;
    private final long maxWaitMs;
    private final long minSliceMs;

    private final Map<String, Semaphore> permits = new ConcurrentHashMap<>();
    private final LongAdder permitted = new LongAdder();
    private final LongAdder rejected = new LongAdder();

    public SemaphoreBulkhead(int maxConcurrentCalls, long maxWaitMs, long minSliceMs) {
        this.maxConcurrentCalls = maxConcurrentCalls;
        this.maxWaitMs = maxWaitMs;
        this.minSliceMs = minSliceMs;
    }

    @Override
    public <T> T call(String key, Callable<T> work) throws Exception {
        // Not fair. A fair semaphore hands permits out FIFO, which sounds
        // desirable and costs a context switch per handoff plus a guarantee we
        // do not need - under a deadline budget the requests that have been
        // waiting longest are the ones closest to being worthless anyway.
        Semaphore semaphore = permits.computeIfAbsent(key, k -> new Semaphore(maxConcurrentCalls));

        long waitMs = waitBudgetMs();
        if (waitMs <= 0 || !semaphore.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) {
            rejected.increment();
            throw new BulkheadFullException(key, Math.max(waitMs, 0));
        }

        permitted.increment();
        try {
            return work.call();
        } finally {
            // In a finally, always. A permit leaked on the exception path is
            // permanent: the bulkhead shrinks by one every time the provider
            // fails, and a service that has been up for a week has silently
            // throttled itself to nothing for reasons no metric explains.
            semaphore.release();
        }
    }

    /**
     * How long it is worth waiting: the configured maximum, capped so that a
     * permit acquired at the last moment still leaves time to make the call.
     */
    private long waitBudgetMs() {
        Deadline deadline = Deadlines.current().orElse(null);
        if (deadline == null) {
            return maxWaitMs;
        }
        return Math.min(maxWaitMs, deadline.remainingMs() - minSliceMs);
    }

    @Override
    public String kind() {
        return "semaphore";
    }

    @Override
    public Map<String, Integer> available() {
        return permits.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, e -> e.getValue().availablePermits()));
    }

    @Override
    public long permitted() {
        return permitted.sum();
    }

    @Override
    public long rejected() {
        return rejected.sum();
    }
}
