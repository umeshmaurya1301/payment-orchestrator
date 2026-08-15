package com.payorch.infra.resilience.retry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Publishes what the retrier actually did.
 *
 * <p>Without these, a retry layer is unfalsifiable. "Retries are capped at 10%
 * of traffic" is a claim about a number nobody can see, and the two states that
 * matter most - the budget refusing retries, and a failure being refused for
 * want of an idempotency reference - are silent by design, because logging
 * either per occurrence would produce a line per request at exactly the moment
 * the logs are least readable.
 *
 * <p>Gauges rather than counters for the totals: these are monotonic in-memory
 * sums, and a gauge reading them is cheaper than mirroring every increment into
 * a second data structure. The scrape interval decides the resolution, which for
 * an experiment sampled every two seconds is ample.
 */
public class RetryMetrics implements MeterBinder {

    private final Retrier retrier;

    public RetryMetrics(Retrier retrier) {
        this.retrier = retrier;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "payorch.retry.attempted",
                "Retries actually attempted", r -> r.retriesAttempted());

        gauge(registry, "payorch.retry.succeeded",
                "Calls that succeeded only because of a retry", r -> r.succeededAfterRetry());

        // The three refusal reasons are separate series on purpose. They mean
        // completely different things and demand different responses: a rising
        // budget refusal is the system protecting a struggling provider and is
        // working as designed; a rising reference refusal is a programming
        // error; a rising deadline refusal means budgets are too tight for the
        // configured backoff.
        gauge(registry, "payorch.retry.refused.classification",
                "Failures classified as not retryable", r -> r.refusedByClassification());

        gauge(registry, "payorch.retry.refused.deadline",
                "Retries that would not fit in the remaining budget", r -> r.refusedByDeadline());

        gauge(registry, "payorch.retry.refused.no_reference",
                "Possibly-processed failures refused for want of an idempotency reference",
                r -> r.refusedWithoutReference());

        gauge(registry, "payorch.retry.budget.granted",
                "Retries the budget allowed", r -> r.budget().grantedRetries());

        gauge(registry, "payorch.retry.budget.denied",
                "Retries the budget refused - the storm that did not happen",
                r -> r.budget().deniedRetries());

        io.micrometer.core.instrument.Gauge
                .builder("payorch.retry.budget.tokens", retrier,
                        r -> r.budget().availableTokens())
                .description("Retry budget tokens remaining; zero means every retry is being refused")
                .register(registry);
    }

    private void gauge(MeterRegistry registry, String name, String description,
                       java.util.function.ToDoubleFunction<Retrier> value) {
        io.micrometer.core.instrument.Gauge.builder(name, retrier, value)
                .description(description)
                .register(registry);
    }
}
