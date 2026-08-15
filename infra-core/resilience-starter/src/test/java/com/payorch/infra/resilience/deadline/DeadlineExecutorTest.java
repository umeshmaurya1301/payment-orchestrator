package com.payorch.infra.resilience.deadline;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadlineExecutorTest {

    private final DeadlineExecutor executor = new DeadlineExecutor(50, 30_000);

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void returnsTheResultWhenThereIsBudget() throws Exception {
        String result = Deadlines.runWith(Deadline.of(5_000), () -> executor.callWithin("work", () -> "done"));

        assertThat(result).isEqualTo("done");
    }

    /**
     * <strong>The assertion the whole design rests on.</strong>
     *
     * <p>It is not enough that the caller is released on time - {@code
     * CompletableFuture.orTimeout} does that and leaves the work running. The
     * abandoned task must actually be <em>interrupted</em>, because that is what
     * makes an HTTP client abort its exchange and hand the connection back. A
     * timeout that releases the caller and leaks the connection would have fixed
     * nothing in the phase-2 baseline, which died of accumulated in-flight work.
     */
    @Test
    void abandonedWorkIsInterruptedNotMerelyAbandoned() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        CountDownLatch observed = new CountDownLatch(1);

        assertThatThrownBy(() -> Deadlines.runWith(Deadline.of(150), () ->
                executor.callWithin("hangs", () -> {
                    started.countDown();
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                        observed.countDown();
                        throw e;
                    }
                    return "never";
                })))
                .isInstanceOf(DeadlineExceededException.class);

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(observed.await(2, TimeUnit.SECONDS))
                .as("the abandoned task must be interrupted, or the connection it holds is never released")
                .isTrue();
        assertThat(interrupted).isTrue();
    }

    @Test
    void abandonedCallsAreReportedAsStarted() {
        assertThatThrownBy(() -> Deadlines.runWith(Deadline.of(120), () ->
                executor.callWithin("hangs", () -> {
                    Thread.sleep(30_000);
                    return "never";
                })))
                .isInstanceOfSatisfying(DeadlineExceededException.class, e -> {
                    assertThat(e.wasStarted())
                            .as("the call went out; the outcome is unknown, not failed")
                            .isTrue();
                    assertThat(e).hasMessageContaining("outcome unknown");
                });

        assertThat(executor.abandonedCalls()).isEqualTo(1);
    }

    /**
     * The other half of the distinction. Too little budget to start means
     * nothing was sent, which a payment may treat as a definite non-event.
     */
    @Test
    void aCallWithTooLittleBudgetIsDeclinedWithoutBeingStarted() {
        AtomicBoolean ran = new AtomicBoolean(false);

        assertThatThrownBy(() -> Deadlines.runWith(Deadline.of(10), () ->
                executor.callWithin("authorize", () -> {
                    ran.set(true);
                    return "should not happen";
                })))
                .isInstanceOfSatisfying(DeadlineExceededException.class, e -> {
                    assertThat(e.wasStarted()).isFalse();
                    assertThat(e).hasMessageContaining("not starting");
                });

        assertThat(ran).as("the work must never have been invoked").isFalse();
        assertThat(executor.declinedCalls()).isEqualTo(1);
        assertThat(executor.abandonedCalls()).isZero();
    }

    /**
     * Classification in 3b branches on the exception type the call threw. If
     * this executor wrapped it in an ExecutionException, every failure would
     * look alike and retry classification would be impossible.
     */
    @Test
    void theCallersOwnExceptionIsPropagatedUnwrapped() {
        assertThatThrownBy(() -> Deadlines.runWith(Deadline.of(5_000), () ->
                executor.callWithin("work", () -> {
                    throw new IllegalStateException("downstream said no");
                })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("downstream said no");
    }

    /**
     * A ScopedValue is not visible to an unrelated executor's threads, so the
     * deadline is captured on the calling thread and re-bound inside the task.
     * Without that, nested calls would find nothing bound and run unbounded -
     * silently, which is the worst way for a bound to be missing.
     */
    @Test
    void theDeadlineIsVisibleInsideTheSubmittedWork() throws Exception {
        Deadline outer = Deadline.of(5_000);

        long seen = Deadlines.runWith(outer, () ->
                executor.callWithin("work", () ->
                        Deadlines.current().orElseThrow().budgetMs()));

        assertThat(seen).isEqualTo(5_000);
    }

    /** Work outside a request must still be bounded by something. */
    @Test
    void withNoBoundDeadlineTheFallbackBudgetApplies() {
        assertThat(Deadlines.current()).isEmpty();

        assertThat(executor.callWithin("work", () -> "done")).isEqualTo("done");
    }
}
