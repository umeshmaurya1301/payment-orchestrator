package com.payorch.orchestrator;

import java.util.EnumMap;
import java.util.Map;

import com.payorch.orchestrator.domain.PaymentState;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * How many payments reached each terminal state.
 *
 * <p><strong>This did not exist until phase 4e, and its absence is the most
 * interesting thing phase 4 found.</strong>
 *
 * <p>Phases 3a to 3f built six resilience components and instrumented every one
 * of them: retries attempted, breaker state, permits free, tokens in the bucket,
 * calls the limiter shed. Twenty-odd series, and not one of them says whether a
 * payment worked. Every metric in this system was about a <em>mechanism</em>.
 *
 * <p>It went unnoticed because it looks like it is already covered. The obvious
 * proxy is the HTTP status at the edge, and phase 4e wrote exactly that alert -
 * 5xx as a share of requests - before discovering that during a chaos run with
 * a provider failing 90% of calls, {@code payments-edge} returned:
 *
 * <pre>
 *   status="201"  count=9,323
 *   status="5xx"  count=0
 * </pre>
 *
 * <p>Every single one a 201. That is not a bug: a declined payment is a
 * <em>business outcome</em>, not a transport error. The request was well formed,
 * it was accepted, it was processed, and the answer was no. Returning 5xx would
 * be wrong - it would tell the merchant's own retry logic that this was our
 * fault and worth retrying, which for a genuine decline it is not.
 *
 * <p>So an HTTP error rate on a payments API is a metric that stays flat through
 * a total provider outage. The one alert a business would actually want was
 * measuring something that cannot move.
 *
 * <h2>Why the state machine and not a boolean</h2>
 *
 * <p>Three terminal states, three separate series, because the difference
 * between them is the entire point of phase 3a:
 *
 * <ul>
 *   <li>{@code AUTHORIZED} - charged.</li>
 *   <li>{@code FAILED} - demonstrably not charged. The merchant may safely
 *       retry.</li>
 *   <li>{@code UNKNOWN} - sent, unanswered, and nobody knows. This is the
 *       expensive one, it is the one experiment 01 exists to reduce, and
 *       collapsing it into "error" alongside FAILED would destroy exactly the
 *       distinction the deadline budget was built to preserve.</li>
 * </ul>
 *
 * <p>Counters, not gauges - see {@code RetryMetrics} for what that costs when
 * you get it the wrong way round.
 *
 * <p><strong>No {@code paymentId} or {@code merchantId} tag.</strong> The phase-4
 * trap list is explicit: a per-payment label is one time series per payment and
 * will take ClickHouse down. That identity belongs in the trace and the log line,
 * both of which carry it already.
 */
public class PaymentOutcomeMetrics {

    private final Map<PaymentState, Counter> counters = new EnumMap<>(PaymentState.class);

    public PaymentOutcomeMetrics(MeterRegistry registry) {
        // Pre-registered rather than created on first use, so the series exists
        // and reads zero from startup. A counter that springs into existence on
        // its first non-zero value makes "no failures yet" and "not wired up"
        // look identical on a dashboard, and they are not the same thing at all.
        for (PaymentState state : new PaymentState[]{
                PaymentState.AUTHORIZED, PaymentState.FAILED, PaymentState.UNKNOWN}) {
            counters.put(state, Counter.builder("payorch.payment.outcome")
                    .description("Payments reaching a terminal state, by state")
                    .tag("state", state.name())
                    .register(registry));
        }
    }

    /**
     * Records one payment reaching a terminal state.
     *
     * <p>Silently ignores non-terminal states rather than throwing. This is
     * called from the persistence layer inside a transaction that has already
     * decided the payment's fate; a metrics bug must not be able to roll back a
     * recorded authorization.
     */
    public void record(PaymentState state) {
        Counter counter = counters.get(state);
        if (counter != null) {
            counter.increment();
        }
    }
}
