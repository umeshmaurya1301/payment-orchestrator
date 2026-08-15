package com.payorch.infra.resilience.breaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Publishes each breaker's state and failure rate, tagged by provider and
 * operation.
 *
 * <p>Written rather than taken from {@code resilience4j-micrometer}, because
 * that module would arrive with its own opinions about metric names and this
 * needs exactly three series with the tags phase 5's routing will group by.
 *
 * <p>Registration is driven off the registry's {@code onEntryAdded} event, so a
 * breaker created later - a provider added at runtime in 3f - is instrumented
 * without anyone remembering to register it. A metric that exists only for
 * providers configured at boot is a metric that goes quiet exactly when
 * something new is misbehaving.
 */
public class CircuitBreakerMetrics implements MeterBinder {

    private final CircuitBreakers breakers;

    public CircuitBreakerMetrics(CircuitBreakers breakers) {
        this.breakers = breakers;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        breakers.registry().getAllCircuitBreakers().forEach(breaker -> register(registry, breaker));
        breakers.registry().getEventPublisher().onEntryAdded(added ->
                register(registry, added.getAddedEntry()));
    }

    private void register(MeterRegistry registry, CircuitBreaker breaker) {
        // name is "pspId:operation" - split so the two can be grouped
        // independently, which is what phase 5 needs to ask "is THIS provider
        // healthy" without string-matching in a query.
        String[] parts = breaker.getName().split(":", 2);
        Tags tags = Tags.of("psp", parts[0], "operation", parts.length > 1 ? parts[1] : "unknown");

        // Numeric, because a gauge cannot carry a string: 0 closed, 1 open,
        // 2 half-open. Documented here because the encoding is otherwise a
        // magic number in a dashboard.
        Gauge.builder("payorch.breaker.state", breaker,
                        b -> switch (b.getState()) {
                            case OPEN, FORCED_OPEN -> 1;
                            case HALF_OPEN -> 2;
                            default -> 0;
                        })
                .description("0 = closed, 1 = open, 2 = half-open")
                .tags(tags)
                .register(registry);

        Gauge.builder("payorch.breaker.failure.rate", breaker,
                        b -> {
                            float rate = b.getMetrics().getFailureRate();
                            // -1 means "not enough calls to judge", which is a
                            // different thing from 0% and must not be graphed as
                            // a healthy provider.
                            return rate < 0 ? Double.NaN : rate;
                        })
                .description("Percentage of provider faults in the window; NaN below minimum-calls")
                .tags(tags)
                .register(registry);

        Gauge.builder("payorch.breaker.calls.not.permitted", breaker,
                        b -> b.getMetrics().getNumberOfNotPermittedCalls())
                .description("Calls the open breaker refused - load the provider did not receive")
                .tags(tags)
                .register(registry);
    }
}
