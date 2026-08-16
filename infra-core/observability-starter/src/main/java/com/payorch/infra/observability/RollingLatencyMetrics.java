package com.payorch.infra.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Publishes the rolling window so a human can see the number phase 5 will route
 * on.
 *
 * <p>This is a <em>reflection</em> of {@link RollingLatency}, not the source of
 * it. The routing input is the component; these gauges exist so that when phase
 * 5 sends traffic away from a provider, the reason is visible on the same graph
 * as the effect rather than only inferable from the outcome.
 *
 * <p>Tagged by {@code psp} and {@code operation}, both of which are bounded -
 * three providers, a handful of operations. Deliberately <strong>not</strong>
 * tagged by anything per-payment: one series per payment is the cardinality
 * explosion that takes the metrics store down, and it is the failure mode that
 * looks most like diligence right up until it happens.
 *
 * <p>Registered lazily, per key, on first observation. A provider that has never
 * been called has no series rather than a series of zeros - the same distinction
 * {@code percentileMs} makes by returning -1, and for the same reason: "fast"
 * and "silent" must not look alike.
 */
public class RollingLatencyMetrics implements MeterBinder {

    private final RollingLatency latency;
    private final java.util.Set<String> registered = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile MeterRegistry registry;

    public RollingLatencyMetrics(RollingLatency latency) {
        this.latency = latency;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
        latency.keys().forEach(this::register);
    }

    /**
     * Called after recording, so a provider seen for the first time gets its
     * series without waiting for a restart.
     */
    public void ensureRegistered(String key) {
        if (registry != null && !registered.contains(key)) {
            register(key);
        }
    }

    private void register(String key) {
        if (!registered.add(key)) {
            return;
        }
        int split = key.lastIndexOf(':');
        Tags tags = Tags.of(
                "psp", split < 0 ? key : key.substring(0, split),
                "operation", split < 0 ? "unknown" : key.substring(split + 1));

        Gauge.builder("payorch.provider.latency.p99", latency, l -> l.p99Ms(key))
                .description("Rolling P99 for this provider and operation, from merged histogram "
                        + "buckets - the routing input phase 5 consumes. -1 means no recent calls")
                .baseUnit("milliseconds")
                .tags(tags)
                .register(registry);

        Gauge.builder("payorch.provider.latency.p50", latency, l -> l.p50Ms(key))
                .description("Rolling median for this provider and operation")
                .baseUnit("milliseconds")
                .tags(tags)
                .register(registry);

        // The denominator. A P99 over eleven samples is not a P99, and without
        // this series there is no way to tell one from a P99 over eleven
        // thousand - they render identically.
        Gauge.builder("payorch.provider.latency.samples", latency, l -> l.count(key))
                .description("Samples currently inside the rolling window")
                .tags(tags)
                .register(registry);
    }
}
