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

    private final Map<String, ResizableSemaphore> permits = new ConcurrentHashMap<>();
    private final Map<String, Integer> limits = new ConcurrentHashMap<>();
    private final Map<String, Long> waits = new ConcurrentHashMap<>();
    private final LongAdder permitted = new LongAdder();
    private final LongAdder rejected = new LongAdder();

    public SemaphoreBulkhead(int maxConcurrentCalls, long maxWaitMs, long minSliceMs) {
        this.maxConcurrentCalls = maxConcurrentCalls;
        this.maxWaitMs = maxWaitMs;
        this.minSliceMs = minSliceMs;
    }

    /**
     * A {@link Semaphore} whose permit count can be lowered as well as raised.
     *
     * <p>{@code release(n)} adds permits and is public; {@code reducePermits(n)}
     * removes them and is {@code protected}, so a subclass is the only way to
     * shrink a bulkhead. Worth knowing why it is protected: reducing permits
     * below the number currently held is legal and simply leaves the semaphore
     * with a negative balance, which resolves itself as the in-flight calls
     * finish. Nothing is interrupted and nothing is over-admitted - a shrink
     * takes effect as capacity is returned rather than by seizing it.
     */
    private static final class ResizableSemaphore extends Semaphore {
        ResizableSemaphore(int permits) {
            super(permits, false);
        }

        void resize(int from, int to) {
            if (to > from) {
                release(to - from);
            } else if (to < from) {
                reducePermits(from - to);
            }
        }
    }

    /**
     * Changes this provider's limits while the system is running - 3f.
     *
     * <p>The component 3d's experiment argued hardest for. A static limit sized
     * by Little's law from healthy latency becomes a hard throughput ceiling the
     * moment latency degrades: 20 permits against a 3 s provider is 6.7 rps, and
     * 3d measured that converting a merely slow provider into a 93% decline
     * rate. There is no constant that is right both when a provider is healthy
     * and when it is not, so the value has to be able to move.
     *
     * <p>Idempotent: called with the values already in effect it does nothing, so
     * a poller re-reading unchanged configuration is free.
     */
    public void configure(String key, int maxConcurrentCalls, long maxWaitMs) {
        waits.put(key, maxWaitMs);
        limits.compute(key, (k, current) -> {
            int previous = current == null ? this.maxConcurrentCalls : current;
            if (previous != maxConcurrentCalls) {
                semaphoreFor(k, previous).resize(previous, maxConcurrentCalls);
            }
            return maxConcurrentCalls;
        });
    }

    private ResizableSemaphore semaphoreFor(String key, int initialPermits) {
        return permits.computeIfAbsent(key, k -> new ResizableSemaphore(initialPermits));
    }

    @Override
    public <T> T call(String key, Callable<T> work) throws Exception {
        // Not fair. A fair semaphore hands permits out FIFO, which sounds
        // desirable and costs a context switch per handoff plus a guarantee we
        // do not need - under a deadline budget the requests that have been
        // waiting longest are the ones closest to being worthless anyway.
        Semaphore semaphore = semaphoreFor(key, limits.getOrDefault(key, maxConcurrentCalls));

        long waitMs = waitBudgetMs(waits.getOrDefault(key, maxWaitMs));
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
    private long waitBudgetMs(long ceilingMs) {
        Deadline deadline = Deadlines.current().orElse(null);
        if (deadline == null) {
            return ceilingMs;
        }
        return Math.min(ceilingMs, deadline.remainingMs() - minSliceMs);
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
