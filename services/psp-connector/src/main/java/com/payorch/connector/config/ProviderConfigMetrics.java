package com.payorch.connector.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * The config loop, as series.
 *
 * <p>{@code reload.failures} is the one to alert on, and the reason is the shape
 * of the failure rather than its severity. When the poll fails the service keeps
 * running on the last configuration it read, correctly and at full speed - so
 * every other signal looks perfect while the system has quietly stopped
 * accepting changes. An operator widening a bulkhead during an incident would
 * watch nothing happen and conclude the limit was not the problem.
 *
 * <p>The per-provider gauges make a config change visible in the same graph as
 * its effect: {@code payorch.psp.config.bulkhead.limit} stepping from 20 to 200
 * next to the rejection rate falling is the whole of 3f's demonstration in two
 * lines, and it needs no log correlation to read.
 */
public class ProviderConfigMetrics implements MeterBinder {

    private final ProviderConfigStore store;

    public ProviderConfigMetrics(ProviderConfigStore store) {
        this.store = store;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("payorch.psp.config.reloads", store, ProviderConfigStore::reloads)
                .description("Successful polls of psp_config")
                .register(registry);

        Gauge.builder("payorch.psp.config.changes", store, ProviderConfigStore::changesApplied)
                .description("Config changes actually pushed into the live components")
                .register(registry);

        Gauge.builder("payorch.psp.config.reload.failures", store, ProviderConfigStore::failures)
                .description("Failed polls - the service is running on stale config and looks healthy")
                .register(registry);

        Gauge.builder("payorch.psp.config.providers", store, s -> s.all().size())
                .description("Providers known to this service")
                .register(registry);

        // Registered per provider on first bind. A provider added later appears
        // when the registry is next bound, which for this system is a restart -
        // acceptable, because a provider that did not exist at startup has no
        // history to plot anyway, and the actuator endpoint reports it
        // immediately either way.
        store.all().forEach((pspId, config) -> {
            Tags tags = Tags.of("psp", pspId);

            Gauge.builder("payorch.psp.config.bulkhead.limit", store,
                            s -> s.find(pspId).map(ProviderConfig::bulkheadMaxConcurrent).orElse(0))
                    .description("Concurrent calls permitted to this provider, as currently in force")
                    .tags(tags)
                    .register(registry);

            Gauge.builder("payorch.psp.config.egress.tps", store,
                            s -> s.find(pspId).map(ProviderConfig::egressTps).orElse(0))
                    .description("This provider's contracted TPS, as currently in force")
                    .tags(tags)
                    .register(registry);

            Gauge.builder("payorch.psp.config.breaker.threshold", store,
                            s -> s.find(pspId)
                                    .map(ProviderConfig::breakerFailureRateThreshold).orElse(0))
                    .description("Failure rate at which this provider's breaker opens")
                    .tags(tags)
                    .register(registry);

            Gauge.builder("payorch.psp.config.updated.epoch", store,
                            s -> s.find(pspId)
                                    .map(row -> row.updatedAt().getEpochSecond()).orElse(0L))
                    .description("When this provider's row last changed, as epoch seconds - "
                            + "a step here dates a config change against every other series")
                    .tags(tags)
                    .register(registry);

            Gauge.builder("payorch.psp.config.enabled", store,
                            s -> s.find(pspId).filter(ProviderConfig::enabled).isPresent() ? 1 : 0)
                    .description("1 when this provider is routable")
                    .tags(tags)
                    .register(registry);
        });
    }
}
