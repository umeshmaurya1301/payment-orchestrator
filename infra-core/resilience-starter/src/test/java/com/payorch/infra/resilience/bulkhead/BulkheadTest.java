package com.payorch.infra.resilience.bulkhead;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.payorch.infra.resilience.deadline.Deadline;
import com.payorch.infra.resilience.deadline.Deadlines;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both implementations must satisfy the same contract, so most of these are
 * parameterised over the two. The differences between them are measured in
 * {@code docs/experiments/04-bulkhead.md}, not asserted here - a unit test is
 * the wrong instrument for "which costs more platform threads under load".
 */
class BulkheadTest {

    static List<Bulkhead> implementations() {
        return List.of(
                new SemaphoreBulkhead(2, 100, 10),
                new ThreadPoolBulkhead(2, 2, 100, 10));
    }

    /**
     * How many calls each implementation admits before refusing.
     *
     * <p>Not the same as the concurrency limit: the thread-pool version also has
     * a queue, so it accepts {@code limit + queueCapacity} before it starts
     * rejecting. Tests that need a <em>saturated</em> bulkhead have to fill that
     * too, and hard-coding 2 for both was quietly testing nothing on one of them.
     */
    static int admissionCapacity(Bulkhead bulkhead) {
        return bulkhead instanceof ThreadPoolBulkhead ? 4 : 2;
    }

    /** Fills the bulkhead and returns the latch that releases it. */
    private static CountDownLatch saturate(Bulkhead bulkhead, ExecutorService callers) throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(2);

        for (int i = 0; i < admissionCapacity(bulkhead); i++) {
            callers.submit(() -> bulkhead.call("mockpsp", () -> {
                running.countDown();
                hold.await();
                return "held";
            }));
        }
        // Both running slots taken...
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        // ...and a moment for the queued remainder to actually reach the queue,
        // which nothing observable can signal because those tasks never start.
        Thread.sleep(300);
        return hold;
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void callsUnderTheLimitPassThrough(Bulkhead bulkhead) throws Exception {
        assertThat(bulkhead.call("mockpsp", () -> "ok")).isEqualTo("ok");
        assertThat(bulkhead.permitted()).isEqualTo(1);
        assertThat(bulkhead.rejected()).isZero();
    }

    /**
     * The property the whole component exists for: beyond the limit, work is
     * <em>rejected</em> rather than queued into the heap. A bulkhead that queues
     * indefinitely has only moved the phase-2 failure, not fixed it.
     */
    @ParameterizedTest
    @MethodSource("implementations")
    void callsBeyondTheLimitAreRejectedRatherThanQueuedForever(Bulkhead bulkhead) throws Exception {
        AtomicInteger rejections = new AtomicInteger();

        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch hold = saturate(bulkhead, callers);

            // Everything beyond the limit must be refused, not parked forever.
            for (int i = 0; i < 6; i++) {
                callers.submit(() -> {
                    try {
                        bulkhead.call("mockpsp", () -> "should not run");
                    } catch (BulkheadFullException e) {
                        rejections.incrementAndGet();
                    } catch (Exception ignored) {
                        // other failures are not what this test is about
                    }
                    return null;
                });
            }

            Thread.sleep(1_500);
            assertThat(rejections.get()).isPositive();
            hold.countDown();
        }
    }

    /**
     * The regression test for the defect that invalidated the first 3d
     * thread-pool run.
     *
     * <p>{@code maxWaitMs} bounds the wait for <em>capacity</em>. It must not
     * bound the call itself - that is the deadline's job, and a provider that
     * takes longer than the bulkhead's wait ceiling is completely ordinary. The
     * original thread-pool version wrote {@code future.get(maxWaitMs)}, which
     * conflated the two: with a 250 ms ceiling and a provider slowed to 3 s it
     * abandoned <em>every</em> call at 250 ms while still sending all of them.
     *
     * <p>The semaphore version passed this from the beginning, which is exactly
     * why it needs to be parameterised - the bug was in the difference between
     * two classes that were supposed to be interchangeable.
     */
    @ParameterizedTest
    @MethodSource("implementations")
    void workSlowerThanTheWaitCeilingStillCompletes(Bulkhead bulkhead) throws Exception {
        String result = Deadlines.runWith(Deadline.of(5_000), () ->
                bulkhead.call("mockpsp", () -> {
                    Thread.sleep(400);          // four times the 100ms wait ceiling
                    return "authorized";
                }));

        assertThat(result).isEqualTo("authorized");
        assertThat(bulkhead.rejected())
                .as("a slow call is not a full bulkhead")
                .isZero();
    }

    /**
     * {@link BulkheadFullException} promises the caller that nothing was sent,
     * and the connector turns that promise into a definite {@code FAILED}. If it
     * can be thrown after the work has been dispatched, then a payment the
     * provider is busy authorising gets recorded as a decline - the
     * {@code FAILED}-vs-{@code UNKNOWN} collapse that phase 3a exists to
     * prevent, reintroduced by a bulkhead.
     */
    @ParameterizedTest
    @MethodSource("implementations")
    void rejectionMeansTheWorkNeverRan(Bulkhead bulkhead) throws Exception {
        AtomicInteger dispatched = new AtomicInteger();

        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch hold = saturate(bulkhead, callers);

            assertThatThrownBy(() -> bulkhead.call("mockpsp", () -> {
                dispatched.incrementAndGet();
                return "should not run";
            })).isInstanceOf(BulkheadFullException.class);

            assertThat(dispatched.get())
                    .as("rejected calls must not reach the provider")
                    .isZero();
            hold.countDown();
        }
    }

    /**
     * One provider saturating must not starve another. Without per-key
     * isolation a single sick provider consumes the whole limit and takes the
     * healthy ones down with it - which is the original meaning of the word.
     */
    @ParameterizedTest
    @MethodSource("implementations")
    void oneProviderSaturatingDoesNotStarveAnother(Bulkhead bulkhead) throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch occupied = new CountDownLatch(2);

        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 2; i++) {
                callers.submit(() -> bulkhead.call("sick", () -> {
                    occupied.countDown();
                    hold.await();
                    return "held";
                }));
            }
            assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(bulkhead.call("healthy", () -> "still works")).isEqualTo("still works");
            hold.countDown();
        }
    }

    /**
     * A permit released only on the happy path shrinks the bulkhead by one on
     * every failure. A service up for a week would silently throttle itself to
     * nothing, and no metric would explain why.
     */
    @ParameterizedTest
    @MethodSource("implementations")
    void aFailingCallStillReleasesItsPermit(Bulkhead bulkhead) throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> bulkhead.call("mockpsp", () -> {
                throw new IllegalStateException("provider said no");
            })).isInstanceOf(IllegalStateException.class);
        }

        assertThat(bulkhead.call("mockpsp", () -> "ok")).isEqualTo("ok");
        assertThat(bulkhead.available().get("mockpsp"))
                .as("all permits back after ten failures")
                .isEqualTo(2);
    }

    /**
     * The composition point with 3a. Waiting 250 ms for a permit when the
     * request has 30 ms left is 250 ms of a connection held to produce something
     * nobody is waiting for.
     */
    @ParameterizedTest
    @MethodSource("implementations")
    void theWaitIsCappedByTheRemainingBudget(Bulkhead bulkhead) throws Exception {
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch hold = saturate(bulkhead, callers);

            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> Deadlines.runWith(Deadline.of(20), () ->
                    bulkhead.call("mockpsp", () -> "should not run")))
                    .isInstanceOf(BulkheadFullException.class);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(elapsedMs)
                    .as("must give up on the budget, not wait the full 100ms ceiling")
                    .isLessThan(80);
            hold.countDown();
        }
    }

    @Test
    void theKindIsReportedForMetricsAndStartupLogging() {
        assertThat(new SemaphoreBulkhead(2, 100, 10).kind()).isEqualTo("semaphore");
        assertThat(new ThreadPoolBulkhead(2, 2, 100, 10).kind()).isEqualTo("threadpool");
    }

    /**
     * The cost the semaphore version does not pay. Asserted rather than
     * described, because it is the crux of 3d's comparison.
     */
    @Test
    void onlyTheThreadPoolVersionAllocatesPlatformThreads() throws Exception {
        ThreadPoolBulkhead pooled = new ThreadPoolBulkhead(4, 4, 100, 10);
        assertThat(pooled.platformThreads()).isZero();

        pooled.call("mockpsp", () -> "ok");

        assertThat(pooled.platformThreads())
                .as("a platform thread per concurrent call, against zero for a semaphore")
                .isPositive();
        pooled.shutdown();
    }

    /**
     * A ScopedValue does not cross a thread handoff. The thread-pool version
     * re-binds it explicitly; without that the work would run with no deadline
     * bound at all - silently, which is the worst way for a bound to be missing.
     */
    @Test
    void theDeadlineSurvivesTheThreadPoolHandoff() throws Exception {
        ThreadPoolBulkhead pooled = new ThreadPoolBulkhead(2, 2, 500, 10);

        long seen = Deadlines.runWith(Deadline.of(5_000), () ->
                pooled.call("mockpsp", () -> Deadlines.current().orElseThrow().budgetMs()));

        assertThat(seen).isEqualTo(5_000);
        pooled.shutdown();
    }
}
