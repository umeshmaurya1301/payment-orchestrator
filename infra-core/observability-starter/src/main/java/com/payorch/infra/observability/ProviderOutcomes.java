package com.payorch.infra.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * Rolling per-provider success rate, keyed the same way {@link ProviderLatency}
 * is, and published as a gauge.
 *
 * <h2>What counts as a failure here, and what deliberately does not</h2>
 *
 * <p>A <strong>decline is a success.</strong> The provider was asked a question,
 * it answered promptly and correctly, and the answer was no. Counting declines
 * against a provider's health would route traffic away from whichever provider
 * is best at refusing stolen cards, which is precisely backwards.
 *
 * <p>A <strong>fault is a failure</strong>: the call threw. No answer, a refused
 * connection, a 5xx, a deadline abandoned. These are the cases where the
 * provider - not the payment - is the thing that went wrong, and they are the
 * only ones that should move traffic elsewhere.
 *
 * <p>This is the same line 3b's {@code FailureClassifier} draws for retries, and
 * drawing it in the same place twice is not duplication: retrying and rerouting
 * are different decisions that happen to agree about what a provider fault is.
 * If they ever disagree, that will be a deliberate change to one of them rather
 * than an accident in both.
 */
public class ProviderOutcomes {

    private final RollingOutcomes outcomes;
    private final MeterRegistry registry;

    public ProviderOutcomes(RollingOutcomes outcomes, MeterRegistry registry) {
        this.outcomes = outcomes;
        this.registry = registry;
    }

    public void record(String pspId, String operation, boolean success) {
        String key = RollingLatency.key(pspId, operation);
        boolean firstSighting = outcomes.samples(key) == 0;
        outcomes.record(key, success);
        if (firstSighting) {
            registerGauges(pspId, operation, key);
        }
    }

    public double successRate(String pspId, String operation) {
        return outcomes.successRate(RollingLatency.key(pspId, operation));
    }

    public long samples(String pspId, String operation) {
        return outcomes.samples(RollingLatency.key(pspId, operation));
    }

    /**
     * Registered on first sighting rather than up front, because the set of
     * providers comes from {@code psp_config} and can grow while running - 3f
     * made that a supported operation rather than a restart.
     *
     * <p>Micrometer de-duplicates by name and tags, so the re-registration a
     * race here could cause is a no-op rather than a leak.
     */
    private void registerGauges(String pspId, String operation, String key) {
        Tags tags = Tags.of("psp", pspId, "operation", operation);

        Gauge.builder("payorch.provider.success.rate", outcomes, o -> o.successRate(key))
                .description("Rolling success rate for this provider; -1 means no recent calls, "
                        + "which is not the same as failing")
                .tags(tags)
                .register(registry);

        Gauge.builder("payorch.provider.outcome.samples", outcomes, o -> o.samples(key))
                .description("Calls inside the rolling window - how much the success rate is worth")
                .tags(tags)
                .register(registry);
    }
}
