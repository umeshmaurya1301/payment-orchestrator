package com.payorch.orchestrator.routing;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * How fresh the routing decision's evidence is.
 *
 * <p>The failure this exists to make visible: {@link ProviderHealthStore} fails
 * open, so when the connector stops answering the orchestrator keeps routing on
 * the last picture it had. That is the right behaviour and it is indistinguishable
 * from working - traffic keeps flowing, payments keep succeeding, and the router
 * is steering on a view that gets older every second.
 *
 * <p>{@code age} is therefore the series to alert on, not {@code failures}. A
 * handful of failed polls is a dropped scrape; an age that keeps climbing means
 * the router has quietly reverted to a snapshot of the past, and after 30 s it
 * silently reverts further, to phase 1's static priority order.
 */
public class RoutingMetrics implements MeterBinder {

    private final ProviderHealthStore store;

    public RoutingMetrics(ProviderHealthStore store) {
        this.store = store;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("payorch.routing.health.age", store, ProviderHealthStore::ageMs)
                .description("Milliseconds since the last successful health poll; "
                        + "-1 before the first. Past 30s the router falls back to static priority")
                .baseUnit("milliseconds")
                .register(registry);

        Gauge.builder("payorch.routing.health.providers", store, s -> s.scores().size())
                .description("Providers the router currently has a health score for. "
                        + "Zero means it is routing on priority alone")
                .register(registry);

        FunctionCounter.builder("payorch.routing.health.polls", store, ProviderHealthStore::polls)
                .description("Health polls attempted")
                .register(registry);

        FunctionCounter.builder("payorch.routing.health.failures", store, ProviderHealthStore::failures)
                .description("Health polls that failed - the router is running on a stale view")
                .register(registry);
    }
}
