package com.payorch.infra.resilience.breaker;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One circuit breaker per provider <strong>per operation</strong>.
 *
 * <h2>Why per operation and not per provider</h2>
 *
 * {@code authorize} and {@code status} fail independently, and treating them as
 * one signal breaks the recovery path. A provider whose authorization path is
 * down may still answer status queries perfectly - and status is exactly what
 * resolves an {@code UNKNOWN} payment into a terminal state.
 *
 * <p>A single per-provider breaker would open on failing authorizations and then
 * refuse the status calls that were the only way to find out what had happened
 * to the payments already in flight. The system would lose visibility into its
 * own exposure at the precise moment that exposure was growing. Phase 8's status
 * poller depends on this separation existing.
 *
 * <h2>Time-based window, not count-based</h2>
 *
 * With a count-based window of 100 calls, the breaker's memory is 0.5 seconds at
 * 200 rps and 50 seconds at 2 rps. "Recent" would mean something different
 * depending on traffic, and this system's traffic varies by two orders of
 * magnitude between a smoke script and a ramp. A time-based window makes the
 * question - "how has this provider behaved in the last 30 seconds" - mean the
 * same thing at every rate. {@code minimumNumberOfCalls} covers the low-traffic
 * case where a time window holds too little to judge.
 *
 * <h2>No slow-call threshold</h2>
 *
 * Resilience4j can trip on slow calls as well as failed ones, and it is not
 * configured here because 3a already covers it: a call that exceeds the deadline
 * is abandoned and surfaces as a failure, which this breaker counts. Adding a
 * second, independently-tuned slowness rule would give two mechanisms with two
 * thresholds for one condition, and the interesting failures would land in the
 * gap between them.
 */
public class CircuitBreakers {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakers.class);

    private final CircuitBreakerRegistry registry;
    private final Consumer<BreakerStateChange> onStateChange;

    public CircuitBreakers(CircuitBreakerConfig config, Consumer<BreakerStateChange> onStateChange) {
        this.registry = CircuitBreakerRegistry.of(config);
        this.onStateChange = onStateChange;

        // Registered on the registry rather than per instance, so breakers
        // created later - a provider added at runtime in 3f - are covered
        // without anyone remembering to subscribe them.
        registry.getEventPublisher().onEntryAdded(added ->
                added.getAddedEntry().getEventPublisher().onStateTransition(event -> {
                    CircuitBreaker.State from = event.getStateTransition().getFromState();
                    CircuitBreaker.State to = event.getStateTransition().getToState();
                    log.warn("circuit breaker '{}' {} -> {}", event.getCircuitBreakerName(), from, to);
                    onStateChange.accept(new BreakerStateChange(
                            event.getCircuitBreakerName(), from.name(), to.name()));
                }));
    }

    public static CircuitBreakerConfig config(int failureRateThreshold,
                                              Duration windowSize,
                                              int minimumCalls,
                                              Duration waitInOpen,
                                              int halfOpenPermits) {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize((int) windowSize.toSeconds())
                .minimumNumberOfCalls(minimumCalls)
                .waitDurationInOpenState(waitInOpen)
                .permittedNumberOfCallsInHalfOpenState(halfOpenPermits)
                // Without this, an open breaker only moves to half-open when a
                // call arrives to discover it should. A provider that recovers
                // during a quiet period would stay open until traffic returned,
                // and the first request back would pay the probe. Recovery
                // should not depend on someone happening to ask.
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // What counts as the provider's fault, rather than "any
                // exception". See ProviderFault for the two rows that matter.
                .recordException(new ProviderFault())
                .build();
    }

    /**
     * @param pspId     the provider
     * @param operation {@code authorize}, {@code capture}, {@code status}
     */
    public CircuitBreaker forOperation(String pspId, String operation) {
        return registry.circuitBreaker(name(pspId, operation));
    }

    /**
     * Runs {@code work} through the breaker for this provider and operation.
     *
     * @throws CallNotPermittedException when the breaker is open. Deliberately
     *         not translated here: the caller decides what an open breaker means
     *         for its domain, and for a payment that decision - is this
     *         {@code FAILED} or {@code UNKNOWN}? - is not one a generic library
     *         should make. It is {@code FAILED}: an open breaker means nothing
     *         was sent.
     */
    public <T> T call(String pspId, String operation, Callable<T> work) throws Exception {
        return forOperation(pspId, operation).executeCallable(work);
    }

    public CircuitBreaker.State state(String pspId, String operation) {
        return forOperation(pspId, operation).getState();
    }

    /** Every breaker's current state, for the actuator endpoint and for tests. */
    public Map<String, String> states() {
        return registry.getAllCircuitBreakers().stream()
                .collect(java.util.stream.Collectors.toMap(
                        CircuitBreaker::getName, breaker -> breaker.getState().name()));
    }

    public CircuitBreakerRegistry registry() {
        return registry;
    }

    public static String name(String pspId, String operation) {
        return pspId + ":" + operation;
    }

    /**
     * A breaker changing state.
     *
     * <p>Published because phase 5 consumes it as a routing input. That is what
     * makes the answer to "your breaker opened - now what?" something better
     * than "we return an error": the routing weight for that provider drops and
     * traffic drains to another one. Emitting the event now, before anything
     * listens, is what makes that a wiring change later rather than a redesign.
     */
    public record BreakerStateChange(String breaker, String from, String to) {
    }
}
