package com.payorch.infra.resilience.retry;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
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
 * <p><strong>{@link FunctionCounter}, not {@link Gauge}, for the totals.</strong>
 * These are monotonic in-memory sums and reading them through a function is
 * cheaper than mirroring every increment into a second data structure - that
 * part of the original reasoning stands, and {@code FunctionCounter} keeps it.
 * What it adds is the <em>type</em>.
 *
 * <p>They were gauges until phase 4e, and it was invisible for as long as the
 * only consumer was a Prometheus scrape, because you compute the delta yourself
 * from the sample values and a cumulative gauge answers that perfectly well.
 * Push the same series into a system that dispatches on declared type and it
 * stops being harmless: SigNoz reported {@code increase} of 36 over a window in
 * which the counter never moved off 4, having summed nine raw samples instead of
 * differencing them. No error, no warning - just a number with the right shape
 * and no meaning, on the panel you would use to decide whether a limiter is
 * saturating.
 *
 * <p>Declaring the metric a Sum in SigNoz's metadata API does <em>not</em> fix
 * it: the query path reads the temporality label carried on each sample, which
 * is written at ingest. The fix has to happen at the emitter, which is here.
 *
 * <p>The cost is a rename. Micrometer's Prometheus naming convention appends
 * {@code _total} to counters, so {@code payorch_retry_attempted} became
 * {@code payorch_retry_attempted_total}. Experiments 02, 03 and 04 quote the old
 * names and have been annotated rather than rewritten - they are a record of what
 * was measured, not a live query.
 */
public class RetryMetrics implements MeterBinder {

    private final Retrier retrier;

    public RetryMetrics(Retrier retrier) {
        this.retrier = retrier;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        counter(registry, "payorch.retry.attempted",
                "Retries actually attempted", r -> r.retriesAttempted());

        counter(registry, "payorch.retry.succeeded",
                "Calls that succeeded only because of a retry", r -> r.succeededAfterRetry());

        // The three refusal reasons are separate series on purpose. They mean
        // completely different things and demand different responses: a rising
        // budget refusal is the system protecting a struggling provider and is
        // working as designed; a rising reference refusal is a programming
        // error; a rising deadline refusal means budgets are too tight for the
        // configured backoff.
        counter(registry, "payorch.retry.refused.classification",
                "Failures classified as not retryable", r -> r.refusedByClassification());

        counter(registry, "payorch.retry.refused.deadline",
                "Retries that would not fit in the remaining budget", r -> r.refusedByDeadline());

        counter(registry, "payorch.retry.refused.no_reference",
                "Possibly-processed failures refused for want of an idempotency reference",
                r -> r.refusedWithoutReference());

        counter(registry, "payorch.retry.budget.granted",
                "Retries the budget allowed", r -> r.budget().grantedRetries());

        counter(registry, "payorch.retry.budget.denied",
                "Retries the budget refused - the storm that did not happen",
                r -> r.budget().deniedRetries());

        // A GAUGE, and the only one left here. Tokens go down as well as up -
        // that is what a token bucket is - so this is a level, not a total, and
        // making it a counter would be the same mistake in the other direction.
        Gauge.builder("payorch.retry.budget.tokens", retrier,
                        r -> r.budget().availableTokens())
                .description("Retry budget tokens remaining; zero means every retry is being refused")
                .register(registry);
    }

    private void counter(MeterRegistry registry, String name, String description,
                         java.util.function.ToDoubleFunction<Retrier> value) {
        FunctionCounter.builder(name, retrier, value)
                .description(description)
                .register(registry);
    }
}
