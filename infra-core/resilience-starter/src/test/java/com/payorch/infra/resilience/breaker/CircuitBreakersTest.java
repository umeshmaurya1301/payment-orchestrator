package com.payorch.infra.resilience.breaker;

import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.payorch.infra.resilience.deadline.DeadlineExceededException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakersTest {

    private final List<CircuitBreakers.BreakerStateChange> events = new ArrayList<>();

    private CircuitBreakers breakers(int minimumCalls) {
        return new CircuitBreakers(
                CircuitBreakers.config(50, Duration.ofSeconds(30), minimumCalls,
                        Duration.ofSeconds(10), 5),
                events::add);
    }

    @Test
    void opensOnSustainedProviderFaults() throws Exception {
        CircuitBreakers breakers = breakers(4);

        for (int i = 0; i < 4; i++) {
            try {
                breakers.call("mockpsp", "authorize", () -> {
                    throw new ConnectException("refused");
                });
            } catch (Exception expected) {
                // counted
            }
        }

        assertThat(breakers.state("mockpsp", "authorize")).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> breakers.call("mockpsp", "authorize", () -> "should not run"))
                .isInstanceOf(CallNotPermittedException.class);
    }

    /**
     * <strong>The separation this design exists for.</strong>
     *
     * <p>A provider whose authorize path is dead may still answer status
     * queries, and status is what resolves an {@code UNKNOWN} payment. A single
     * per-provider breaker would refuse exactly the calls needed to find out
     * what happened to the payments already in flight - losing visibility into
     * the exposure at the moment it is growing.
     */
    @Test
    void authorizeAndStatusTripIndependently() throws Exception {
        CircuitBreakers breakers = breakers(4);

        for (int i = 0; i < 4; i++) {
            try {
                breakers.call("mockpsp", "authorize", () -> {
                    throw new ConnectException("refused");
                });
            } catch (Exception expected) {
                // counted
            }
        }

        assertThat(breakers.state("mockpsp", "authorize")).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breakers.state("mockpsp", "status"))
                .as("status must still be reachable to resolve UNKNOWN payments")
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breakers.call("mockpsp", "status", () -> "still works")).isEqualTo("still works");
    }

    @Test
    void providersTripIndependently() throws Exception {
        CircuitBreakers breakers = breakers(4);

        for (int i = 0; i < 4; i++) {
            try {
                breakers.call("providerA", "authorize", () -> {
                    throw new ConnectException("refused");
                });
            } catch (Exception expected) {
                // counted
            }
        }

        assertThat(breakers.state("providerA", "authorize")).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breakers.state("providerB", "authorize")).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // --- what counts as the provider's fault ------------------------------

    /**
     * The most important negative case. A deadline that expired before anything
     * was sent means <em>we</em> were slow. Counting it opens the breaker on a
     * healthy provider exactly when we are overloaded - removing the one
     * dependency that was still working.
     */
    @Test
    void ourOwnBudgetExhaustionDoesNotCountAgainstTheProvider() {
        CircuitBreakers breakers = breakers(4);

        for (int i = 0; i < 20; i++) {
            try {
                breakers.call("mockpsp", "authorize", () -> {
                    throw DeadlineExceededException.notStarted("authorize", 10, 50);
                });
            } catch (Exception expected) {
                // must not be counted
            }
        }

        assertThat(breakers.state("mockpsp", "authorize"))
                .as("nothing was sent, so the provider has done nothing wrong")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /** The same exception type, the other way round: sent and abandoned is theirs. */
    @Test
    void aCallAbandonedInFlightDoesCountAgainstTheProvider() {
        CircuitBreakers breakers = breakers(4);

        for (int i = 0; i < 4; i++) {
            try {
                breakers.call("mockpsp", "authorize", () -> {
                    throw DeadlineExceededException.abandoned("authorize", 5_000);
                });
            } catch (Exception expected) {
                // counted
            }
        }

        assertThat(breakers.state("mockpsp", "authorize")).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /**
     * A 429 is a healthy provider answering promptly to say we are rude. From
     * 3e we generate these ourselves through the egress limiter, and tripping a
     * breaker on them would be self-inflicted.
     */
    @Test
    void beingRateLimitedDoesNotCountAgainstTheProvider() {
        CircuitBreakers breakers = breakers(4);

        for (int i = 0; i < 20; i++) {
            try {
                breakers.call("mockpsp", "authorize", () -> {
                    throw HttpClientErrorException.create(
                            HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null, null, null);
                });
            } catch (Exception expected) {
                // must not be counted
            }
        }

        assertThat(breakers.state("mockpsp", "authorize")).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aServerErrorCounts() {
        CircuitBreakers breakers = breakers(4);

        for (int i = 0; i < 4; i++) {
            try {
                breakers.call("mockpsp", "authorize", () -> {
                    throw HttpServerErrorException.create(
                            HttpStatus.BAD_GATEWAY, "Bad Gateway", null, null, null);
                });
            } catch (Exception expected) {
                // counted
            }
        }

        assertThat(breakers.state("mockpsp", "authorize")).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /**
     * An unattributable failure is more likely ours than theirs, and a breaker
     * that opens on our own bugs takes out a working dependency for a reason
     * nobody can diagnose from the breaker's metrics.
     */
    @Test
    void anUnrecognisedFailureDoesNotCount() {
        CircuitBreakers breakers = breakers(4);

        for (int i = 0; i < 20; i++) {
            try {
                breakers.call("mockpsp", "authorize", () -> {
                    throw new IllegalStateException("a bug in our own mapping code");
                });
            } catch (Exception expected) {
                // must not be counted
            }
        }

        assertThat(breakers.state("mockpsp", "authorize")).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // --- events -----------------------------------------------------------

    /**
     * Phase 5 consumes these as a routing input. That is what turns "your
     * breaker opened - now what?" from "we return an error" into "traffic drains
     * to another provider".
     */
    @Test
    void stateChangesArePublished() {
        CircuitBreakers breakers = breakers(4);

        for (int i = 0; i < 4; i++) {
            try {
                breakers.call("mockpsp", "authorize", () -> {
                    throw new ConnectException("refused");
                });
            } catch (Exception expected) {
                // counted
            }
        }

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().breaker()).isEqualTo("mockpsp:authorize");
        assertThat(events.getFirst().from()).isEqualTo("CLOSED");
        assertThat(events.getFirst().to()).isEqualTo("OPEN");
    }

    @Test
    void statesAreReportableForEveryBreaker() throws Exception {
        CircuitBreakers breakers = breakers(4);
        breakers.call("mockpsp", "authorize", () -> "ok");
        breakers.call("mockpsp", "status", () -> "ok");

        assertThat(breakers.states())
                .containsEntry("mockpsp:authorize", "CLOSED")
                .containsEntry("mockpsp:status", "CLOSED");
    }
}
