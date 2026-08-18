package com.payorch.ledger.consume;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import org.springframework.stereotype.Component;

/**
 * What the consumer actually did, as four monotonic series.
 *
 * <p>Without these, the retry ladder is unfalsifiable in the same way the retry
 * layer was before phase 3b: "failures tier through retry topics and land in the
 * DLQ" is a claim about behaviour nobody can see, and its failure mode is
 * silence. A ladder that is quietly dropping messages and a ladder that is
 * quietly never being reached produce identical logs.
 *
 * <p><strong>{@link FunctionCounter}, not {@code Gauge}.</strong> Phase 4e's
 * finding, applied at the point where it would otherwise be repeated: a
 * cumulative value declared as a gauge is summed rather than differenced by
 * anything that dispatches on declared type, and SigNoz reported an
 * {@code increase} of 36 over a window in which the underlying counter never
 * moved off 4. The metadata API does not fix it; the emitter has to.
 *
 * <p>Registered here rather than inside the consumer so the consumer stays a
 * consumer. It reads through methods it already exposes for the same reason the
 * other binders in this project do - no second data structure to keep in step.
 */
@Component
public class LedgerConsumerMetrics implements MeterBinder {

    private final PaymentEventConsumer consumer;

    public LedgerConsumerMetrics(PaymentEventConsumer consumer) {
        this.consumer = consumer;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        counter(registry, "payorch.ledger.consumed",
                "Events handled by the ledger consumer, first attempt or retry",
                PaymentEventConsumer::consumed);

        // A duplicate is at-least-once working, not a fault. It is worth its own
        // series because the RATE is diagnostic: a climbing duplicate count with
        // a flat consumed count means the relay or a rebalance is redelivering.
        counter(registry, "payorch.ledger.duplicate",
                "Events the unique constraint recognised as already posted",
                PaymentEventConsumer::duplicates);

        // Deliveries that arrived on a retry topic rather than the main one.
        // This is the series that proves the ladder is being used at all - the
        // difference between "no failures" and "failures being dropped".
        counter(registry, "payorch.ledger.retried",
                "Events delivered from a retry tier rather than the main topic",
                PaymentEventConsumer::retried);

        // The number to alert on. Anything here has exhausted 5s, 1m and 10m,
        // so it is not a transient and it is not going to fix itself.
        counter(registry, "payorch.ledger.dead_lettered",
                "Events that exhausted the retry ladder and reached the DLQ",
                PaymentEventConsumer::deadLettered);

        // Must stay at zero. It exists because the DLT handler is wrapped in a
        // catch-all, and a catch-all with no counter is a way to keep running
        // while the last line of defence is broken - which is how four messages
        // became 10,306 DLQ records in phase 6f without anything looking wrong.
        counter(registry, "payorch.ledger.dlt_log_failed",
                "Times the DLT handler's own logging threw - must be zero",
                PaymentEventConsumer::dltLogFailures);
    }

    private void counter(MeterRegistry registry, String name, String description,
                         java.util.function.ToDoubleFunction<PaymentEventConsumer> read) {
        FunctionCounter.builder(name, consumer, read)
                .description(description)
                .register(registry);
    }
}
