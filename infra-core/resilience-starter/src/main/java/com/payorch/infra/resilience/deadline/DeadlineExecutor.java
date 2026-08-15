package com.payorch.infra.resilience.deadline;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a blocking call bounded by whatever is left of the request's budget.
 *
 * <h2>Why this is not a socket timeout</h2>
 *
 * Spring's {@code RestClient} has no per-request timeout: a read timeout belongs
 * to the request factory, and a factory is shared by every call the client
 * makes. A budget is per-request by definition, so the two do not meet. Building
 * a client per call to work around that would rebuild the message converters
 * every time.
 *
 * <h2>Why this is not {@code CompletableFuture.orTimeout}</h2>
 *
 * That is the obvious implementation and it is a trap. {@code orTimeout}
 * completes the <em>future</em> exceptionally; it does not touch the thread
 * doing the work. The caller gets a prompt failure, feels protected, and the
 * abandoned call carries on holding a connection, a slot in the downstream's
 * pool and a chunk of heap until it finishes on its own. That is a timeout that
 * makes the metrics look better and the system no healthier - and given that the
 * phase-2 baseline died of accumulated in-flight work, it would have fixed
 * exactly nothing.
 *
 * <p>So: submit to a virtual thread, wait for the remaining budget, and on
 * expiry {@link Future#cancel(boolean)} with {@code true} to <strong>interrupt
 * </strong> it. The JDK HTTP client responds to interruption by aborting the
 * exchange and releasing the connection, which is the part that actually
 * reclaims the resource. {@code DeadlineExecutorTest} pins that behaviour rather
 * than trusting it.
 *
 * <p>One virtual thread per call is the cost. At the concurrency this system
 * reaches that is measured in kilobytes and no platform threads.
 */
public class DeadlineExecutor {

    private static final Logger log = LoggerFactory.getLogger(DeadlineExecutor.class);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final long minSliceMs;
    private final long fallbackBudgetMs;

    private final LongAdder abandoned = new LongAdder();
    private final LongAdder declined = new LongAdder();

    /**
     * @param minSliceMs       the floor below which a call is declined rather
     *                         than started - see {@link Deadline#hasAtLeast}
     * @param fallbackBudgetMs budget used when there is no bound deadline, so
     *                         work outside a request is still bounded by
     *                         something rather than by nothing
     */
    public DeadlineExecutor(long minSliceMs, long fallbackBudgetMs) {
        this.minSliceMs = minSliceMs;
        this.fallbackBudgetMs = fallbackBudgetMs;
    }

    /**
     * @param operation a name for logs and errors, e.g. {@code "authorize"}
     * @throws DeadlineExceededException if there was too little budget to start,
     *         or the call was started and abandoned. The two are distinguishable
     *         via {@link DeadlineExceededException#wasStarted()}, and for a
     *         payment that distinction decides {@code FAILED} versus
     *         {@code UNKNOWN}.
     */
    public <T> T callWithin(String operation, Callable<T> work) {
        // Captured HERE, on the calling thread. A ScopedValue is not visible to
        // an unrelated executor's threads, so reading it inside the submitted
        // task would find nothing bound and silently run unbounded.
        Deadline deadline = Deadlines.currentOrDefault(fallbackBudgetMs);

        if (!deadline.hasAtLeast(minSliceMs)) {
            declined.increment();
            log.warn("declining '{}': {}ms remaining, {}ms required",
                    operation, deadline.remainingMs(), minSliceMs);
            throw DeadlineExceededException.notStarted(operation, deadline.remainingMs(), minSliceMs);
        }

        long sliceMs = deadline.remainingMs();
        Future<T> future = executor.submit(() -> Deadlines.runWith(deadline, work::call));

        try {
            return future.get(sliceMs, TimeUnit.MILLISECONDS);

        } catch (TimeoutException e) {
            // true: interrupt. Without it this is orTimeout with extra steps -
            // the caller is released and the work keeps holding the connection.
            future.cancel(true);
            abandoned.increment();
            log.warn("abandoned '{}' after {}ms; outcome unknown", operation, sliceMs);
            throw DeadlineExceededException.abandoned(operation, sliceMs);

        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw DeadlineExceededException.abandoned(operation, deadline.elapsedMs());

        } catch (ExecutionException e) {
            // Unwrap, so callers see the exception their own code threw rather
            // than an ExecutionException wrapping it. Classification in 3b
            // depends on the real type being visible here.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    /** Calls abandoned mid-flight. Each one is an unknown outcome. */
    public long abandonedCalls() {
        return abandoned.sum();
    }

    /** Calls never started for want of budget. Each one is a definite non-event. */
    public long declinedCalls() {
        return declined.sum();
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
