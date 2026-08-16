package com.payorch.infra.resilience.bulkhead;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import com.payorch.infra.resilience.deadline.Deadline;
import com.payorch.infra.resilience.deadline.DeadlineExceededException;
import com.payorch.infra.resilience.deadline.Deadlines;

/**
 * A bounded pool of <strong>platform</strong> threads, plus a bounded queue.
 *
 * <p>Built to be measured against {@link SemaphoreBulkhead}, not because it is
 * expected to win. The phase-3 plan's position is that under virtual threads
 * this is usually the wrong choice - you pay platform-thread cost to isolate
 * something that is already cheap - and 3d's job is to produce the numbers
 * rather than repeat the claim.
 *
 * <p>What it costs, concretely:
 *
 * <ul>
 *   <li><strong>Platform threads.</strong> Each is ~1 MB of reserved stack and a
 *       real OS thread. A semaphore bulkhead of the same width costs zero.</li>
 *   <li><strong>A handoff.</strong> Every call crosses a queue to another
 *       thread and back, so the caller's thread is parked waiting for a future
 *       while a second thread does the work. Two threads per in-flight call
 *       instead of one.</li>
 *   <li><strong>Thread-bound context.</strong> The deadline is a
 *       {@link Deadlines ScopedValue}, and MDC is a ThreadLocal - neither
 *       crosses to the pool thread on its own. The deadline is re-bound
 *       explicitly below; anything else that relied on thread affinity silently
 *       does not travel.</li>
 *   <li><strong>The loss of "nothing was sent".</strong> See below. This one
 *       cost a whole experiment run before it was noticed.</li>
 * </ul>
 *
 * <p>Where it genuinely wins is isolating a call that <em>pins</em> its carrier
 * thread - a JNI call, or a library that synchronizes around blocking I/O. Then
 * a semaphore does not isolate anything, because the pinned carrier is the
 * resource actually being exhausted.
 *
 * <h2>Why admission and execution are timed separately</h2>
 *
 * <p>The first version of this class wrote {@code future.get(maxWaitMs)} and
 * called it the bulkhead wait. That is wrong, and wrong in a way that reads as
 * correct: one {@code get} bounds <em>queue wait plus execution together</em>,
 * whereas {@link SemaphoreBulkhead}'s {@code maxWaitMs} bounds only the wait for
 * a permit and then lets the work run under the request's deadline. The two
 * classes were not implementing the same contract, so the 3d comparison was
 * measuring the difference between two timeout policies rather than between two
 * isolation mechanisms.
 *
 * <p>Under a 250 ms wait ceiling and a provider slowed to 3 s, every single call
 * was dispatched, sent to the provider, and then abandoned 250 ms later: 100 %
 * failure upstream while the provider received the full 12,458 requests. Neither
 * shed nor served.
 *
 * <p>Worse than the numbers: it threw {@link BulkheadFullException} after the
 * request had gone out. That exception's contract is <em>nothing was sent</em>,
 * which the connector maps to a definite decline - so those payments were
 * recorded {@code FAILED} while the provider was busy authorising the cards.
 * That is exactly the {@code FAILED}-vs-{@code UNKNOWN} collapse phase 3a exists
 * to prevent, reintroduced two sub-phases later by a single misplaced timeout.
 *
 * <p>So: the timed {@link ArrayBlockingQueue#offer offer} below bounds admission
 * and nothing else, and it can only fail before the task exists. Once a task is
 * admitted, waiting for it is the deadline's job, and giving up on it is
 * reported as {@link DeadlineExceededException} carrying whether the work had
 * actually begun.
 */
public class ThreadPoolBulkhead implements Bulkhead {

    private final int maxConcurrentCalls;
    private final int queueCapacity;
    private final long maxWaitMs;
    private final long minSliceMs;

    private final Map<String, ThreadPoolExecutor> pools = new ConcurrentHashMap<>();
    private final LongAdder permitted = new LongAdder();
    private final LongAdder rejected = new LongAdder();

    public ThreadPoolBulkhead(int maxConcurrentCalls, int queueCapacity,
                              long maxWaitMs, long minSliceMs) {
        this.maxConcurrentCalls = maxConcurrentCalls;
        this.queueCapacity = queueCapacity;
        this.maxWaitMs = maxWaitMs;
        this.minSliceMs = minSliceMs;
    }

    @Override
    public <T> T call(String key, Callable<T> work) throws Exception {
        ThreadPoolExecutor pool = pools.computeIfAbsent(key, this::newPool);

        // Captured on the calling thread: a ScopedValue is not visible to the
        // pool's threads, so reading it on the far side would find nothing bound
        // and the work would run unbounded. Same hazard DeadlineExecutor has,
        // and it is the clearest illustration of what a handoff costs.
        Deadline deadline = Deadlines.current().orElse(null);
        long admissionWaitMs = admissionWaitMs(deadline);
        if (admissionWaitMs <= 0) {
            rejected.increment();
            throw new BulkheadFullException(key, 0);
        }

        // Flipped by the pool thread immediately before the work runs, so a
        // task we later give up on can say truthfully whether the provider was
        // contacted. Without it the abandoned/not-started distinction would be a
        // guess, and guessing it wrong is a double charge.
        AtomicBoolean started = new AtomicBoolean();
        FutureTask<T> task = new FutureTask<>(() -> {
            started.set(true);
            return deadline == null ? work.call() : Deadlines.runWith(deadline, work::call);
        });

        // Offered straight onto the queue rather than through submit(), because
        // submit() has no timed form - it either takes the task or invokes the
        // rejection handler at once. A timed offer is the whole difference
        // between "the bulkhead is full right now" and "the bulkhead is full and
        // will still be full in 250 ms", and only the second is worth shedding.
        // Legal only because every core thread is prestarted in newPool(); an
        // executor with no live workers would leave queued tasks untouched.
        if (!pool.getQueue().offer(task, admissionWaitMs, TimeUnit.MILLISECONDS)) {
            // The bounded-queue rejection, and the whole reason the queue has a
            // bound: an unbounded one moves the failure from "rejected promptly"
            // to "heap exhausted later". Nothing has been dispatched, so
            // BulkheadFullException is honest here and only here.
            rejected.increment();
            throw new BulkheadFullException(key, admissionWaitMs);
        }

        permitted.increment();
        try {
            return deadline == null ? task.get() : task.get(resultWaitMs(deadline), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            // Past admission, so this is a budget breach and not a capacity
            // decision - and which kind of breach depends on whether a pool
            // thread ever picked the task up.
            throw started.get()
                    ? DeadlineExceededException.abandoned("bulkhead:" + key, resultWaitMs(deadline))
                    : DeadlineExceededException.notStarted("bulkhead:" + key, 0, minSliceMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw new IllegalStateException(cause);
        }
    }

    private ThreadPoolExecutor newPool(String key) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                maxConcurrentCalls, maxConcurrentCalls,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "bulkhead-" + key);
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        // Required, not an optimisation: call() offers onto the queue directly,
        // and a task placed there when no worker exists is never picked up.
        // It also makes the platform-thread cost immediate and visible in the
        // metric rather than something that ramps in quietly under load.
        pool.prestartAllCoreThreads();
        return pool;
    }

    /** How long it is worth queueing for capacity, capped by what is left to use it. */
    private long admissionWaitMs(Deadline deadline) {
        if (deadline == null) {
            return maxWaitMs;
        }
        return Math.min(maxWaitMs, deadline.remainingMs() - minSliceMs);
    }

    /**
     * How long to wait for an admitted task. The deadline's remaining budget,
     * plus one slice of grace: the work is itself running under the same
     * deadline, so in the normal case it reports its own expiry first and this
     * outer bound never fires. It exists so a task that ignores its budget
     * cannot pin the caller's thread indefinitely.
     */
    private long resultWaitMs(Deadline deadline) {
        return Math.max(deadline.remainingMs(), 0) + minSliceMs;
    }

    @Override
    public String kind() {
        return "threadpool";
    }

    @Override
    public Map<String, Integer> available() {
        return pools.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> maxConcurrentCalls - e.getValue().getActiveCount()));
    }

    @Override
    public long permitted() {
        return permitted.sum();
    }

    @Override
    public long rejected() {
        return rejected.sum();
    }

    /** Platform threads currently alive across every pool. The cost, counted. */
    public int platformThreads() {
        return pools.values().stream().mapToInt(ThreadPoolExecutor::getPoolSize).sum();
    }

    public void shutdown() {
        pools.values().forEach(ThreadPoolExecutor::shutdownNow);
    }
}
