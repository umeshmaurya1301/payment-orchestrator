package com.payorch.connector.health;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * {@link SyntheticProber}, as series.
 *
 * <p>Two counters rather than a per-provider breakdown, deliberately small: the
 * question an operator has about this mechanism is "is it running at all" -
 * {@code fired} climbing is the only proof - and "is what it finds credible" -
 * {@code succeeded} against {@code fired} over a window. Both are answerable
 * without a per-provider tag, and a probe cycle that never sees a half-open
 * breaker in an otherwise healthy hour should show {@code fired} flat, which is
 * the correct and boring reading, not a missing series to wonder about.
 */
public class SyntheticProberMetrics implements MeterBinder {

    private final SyntheticProber prober;

    public SyntheticProberMetrics(SyntheticProber prober) {
        this.prober = prober;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        FunctionCounter.builder("payorch.psp.probe.fired", prober, SyntheticProber::fired)
                .description("Synthetic authorize calls made against a half-open provider")
                .register(registry);

        FunctionCounter.builder("payorch.psp.probe.succeeded", prober, SyntheticProber::succeeded)
                .description("Of those, the ones the provider approved or cleanly declined")
                .register(registry);
    }
}
