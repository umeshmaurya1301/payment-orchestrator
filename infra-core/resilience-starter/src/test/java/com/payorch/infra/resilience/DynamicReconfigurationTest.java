package com.payorch.infra.resilience;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.payorch.infra.resilience.breaker.CircuitBreakers;
import com.payorch.infra.resilience.bulkhead.BulkheadFullException;
import com.payorch.infra.resilience.bulkhead.SemaphoreBulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3f: the components have to accept new settings while they are running.
 *
 * <p>These are the primitives {@code ProviderConfigStore} pushes into. The
 * database side of 3f - polling, change detection, the version trigger - is
 * demonstrated end to end in {@code docs/experiments/06-dynamic-config.md}
 * against a live stack, because "without a restart" is a claim about a running
 * process and a unit test cannot make it.
 */
class DynamicReconfigurationTest {

    /**
     * The headline capability. 3d measured a static bulkhead sized from healthy
     * latency turning a merely slow provider into a 93% decline rate; the answer
     * is not a better constant, it is being able to widen the limit during the
     * incident without restarting the service that is already struggling.
     */
    @Test
    void wideningTheBulkheadTakesEffectImmediately() throws Exception {
        SemaphoreBulkhead bulkhead = new SemaphoreBulkhead(2, 50, 10);
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(2);

        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 2; i++) {
                callers.submit(() -> bulkhead.call("psp-a", () -> {
                    running.countDown();
                    hold.await();
                    return "held";
                }));
            }
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> bulkhead.call("psp-a", () -> "no room"))
                    .isInstanceOf(BulkheadFullException.class);

            bulkhead.configure("psp-a", 5, 50);

            assertThat(bulkhead.call("psp-a", () -> "admitted"))
                    .as("the new permits are available to the very next call")
                    .isEqualTo("admitted");
            hold.countDown();
        }
    }

    /**
     * Shrinking is the direction with the subtlety. Reducing permits below the
     * number currently held leaves the semaphore with a negative balance, which
     * resolves as in-flight calls finish - nothing is interrupted, and crucially
     * nothing is over-admitted in the meantime.
     */
    @Test
    void narrowingTheBulkheadDoesNotInterruptCallsAlreadyRunning() throws Exception {
        SemaphoreBulkhead bulkhead = new SemaphoreBulkhead(4, 50, 10);
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(4);
        AtomicInteger completed = new AtomicInteger();

        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 4; i++) {
                callers.submit(() -> bulkhead.call("psp-a", () -> {
                    running.countDown();
                    hold.await();
                    completed.incrementAndGet();
                    return "held";
                }));
            }
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

            bulkhead.configure("psp-a", 1, 50);

            assertThatThrownBy(() -> bulkhead.call("psp-a", () -> "no room"))
                    .as("the narrower limit binds at once")
                    .isInstanceOf(BulkheadFullException.class);

            hold.countDown();
            callers.shutdown();
            assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(completed.get())
                    .as("every call already in flight still finished")
                    .isEqualTo(4);
        }
    }

    /** One provider's limits must not move another's. */
    @Test
    void configuringOneProviderLeavesTheOthersAlone() throws Exception {
        SemaphoreBulkhead bulkhead = new SemaphoreBulkhead(1, 50, 10);
        bulkhead.configure("psp-a", 3, 50);

        assertThat(bulkhead.call("psp-a", () -> "a")).isEqualTo("a");
        assertThat(bulkhead.call("psp-b", () -> "b")).isEqualTo("b");

        assertThat(bulkhead.available().get("psp-a")).isEqualTo(3);
        assertThat(bulkhead.available().get("psp-b"))
                .as("psp-b keeps the default it was never reconfigured away from")
                .isEqualTo(1);
    }

    @Test
    void aBreakerPicksUpItsProvidersThreshold() {
        CircuitBreakers breakers = new CircuitBreakers(
                CircuitBreakers.config(50, Duration.ofSeconds(30), 20, Duration.ofSeconds(10), 5),
                change -> { });

        breakers.reconfigure("psp-b", CircuitBreakers.config(
                30, Duration.ofSeconds(60), 20, Duration.ofSeconds(20), 3));

        CircuitBreaker breaker = breakers.forOperation("psp-b", "authorize");
        assertThat(breaker.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(30f);
        assertThat(breaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(60);

        assertThat(breakers.forOperation("psp-a", "authorize")
                .getCircuitBreakerConfig().getFailureRateThreshold())
                .as("a provider with no override keeps the global default")
                .isEqualTo(50f);
    }

    /**
     * The property that makes a two-second poll safe.
     *
     * <p>Rebuilding a breaker discards its sliding window, so a poller that
     * treated every read as a change would reset every breaker every two seconds
     * and leave them permanently unable to accumulate enough calls to open. The
     * breaker would exist, report healthy, and never fire.
     */
    @Test
    void reconfiguringWithIdenticalSettingsDoesNotResetTheBreaker() {
        CircuitBreakers breakers = new CircuitBreakers(
                CircuitBreakers.config(50, Duration.ofSeconds(30), 20, Duration.ofSeconds(10), 5),
                change -> { });

        breakers.reconfigure("psp-a", CircuitBreakers.config(
                40, Duration.ofSeconds(30), 20, Duration.ofSeconds(10), 5));
        CircuitBreaker before = breakers.forOperation("psp-a", "authorize");
        before.transitionToOpenState();

        // The same settings again, as a poll of an unchanged row would supply.
        breakers.reconfigure("psp-a", CircuitBreakers.config(
                40, Duration.ofSeconds(30), 20, Duration.ofSeconds(10), 5));

        assertThat(breakers.forOperation("psp-a", "authorize"))
                .as("the same instance, so its window and state survive")
                .isSameAs(before);
        assertThat(breakers.state("psp-a", "authorize")).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /**
     * A genuine change does reset the window, and that is a documented cost
     * rather than an oversight - resilience4j fixes a breaker's config at
     * construction. Asserted so the trade-off is recorded in the build and not
     * only in a comment.
     */
    @Test
    void aRealChangeRebuildsTheBreakerAndLosesItsState() {
        CircuitBreakers breakers = new CircuitBreakers(
                CircuitBreakers.config(50, Duration.ofSeconds(30), 20, Duration.ofSeconds(10), 5),
                change -> { });

        breakers.reconfigure("psp-a", CircuitBreakers.config(
                40, Duration.ofSeconds(30), 20, Duration.ofSeconds(10), 5));
        breakers.forOperation("psp-a", "authorize").transitionToOpenState();
        assertThat(breakers.state("psp-a", "authorize")).isEqualTo(CircuitBreaker.State.OPEN);

        breakers.reconfigure("psp-a", CircuitBreakers.config(
                25, Duration.ofSeconds(30), 20, Duration.ofSeconds(10), 5));

        assertThat(breakers.state("psp-a", "authorize"))
                .as("changing a threshold on an open breaker closes it - the cost of no restart")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
