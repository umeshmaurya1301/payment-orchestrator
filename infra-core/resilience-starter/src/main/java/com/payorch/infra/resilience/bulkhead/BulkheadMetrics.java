package com.payorch.infra.resilience.bulkhead;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Publishes admission-control outcomes, tagged with which implementation is in
 * use.
 *
 * <p>The {@code kind} tag is what makes 3d's comparison possible from the
 * captured metrics alone: two runs of the same experiment differ only in that
 * label, so a rejection rate can be attributed to the implementation rather than
 * to whatever else was happening that afternoon.
 *
 * <p>{@code rejected} is the number that matters and the one that looks like bad
 * news. It is not: a rejection is work shed deliberately and promptly, which is
 * the entire point. The failure mode this component prevents does not show up as
 * a rejection - it shows up as a heap dump.
 */
public class BulkheadMetrics implements MeterBinder {

    private final Bulkhead bulkhead;

    public BulkheadMetrics(Bulkhead bulkhead) {
        this.bulkhead = bulkhead;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Tags tags = Tags.of("kind", bulkhead.kind());

        Gauge.builder("payorch.bulkhead.permitted", bulkhead, Bulkhead::permitted)
                .description("Calls admitted")
                .tags(tags)
                .register(registry);

        Gauge.builder("payorch.bulkhead.rejected", bulkhead, Bulkhead::rejected)
                .description("Calls shed because the concurrency limit was reached - work deliberately not done")
                .tags(tags)
                .register(registry);

        // Per provider, because the whole point of keying by provider is that
        // one saturating must not starve another - and a single aggregate
        // number would hide exactly that.
        Gauge.builder("payorch.bulkhead.available.total", bulkhead,
                        b -> b.available().values().stream().mapToInt(Integer::intValue).sum())
                .description("Permits currently free across all providers")
                .tags(tags)
                .register(registry);

        if (bulkhead instanceof ThreadPoolBulkhead pooled) {
            // The cost of this implementation, in the unit that matters. A
            // semaphore bulkhead has no equivalent series because the number
            // would always be zero.
            Gauge.builder("payorch.bulkhead.platform.threads", pooled,
                            ThreadPoolBulkhead::platformThreads)
                    .description("Platform threads allocated by the thread-pool bulkhead")
                    .tags(tags)
                    .register(registry);
        }
    }
}
