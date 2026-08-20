package com.payorch.ledger.saga;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import org.springframework.stereotype.Component;

import com.payorch.ledger.consume.PaymentEventConsumer;

/**
 * The saga, as three series. Phase 6k.
 *
 * <p>{@link FunctionCounter} rather than {@code Gauge}, for phase 4e&#39;s reason:
 * a cumulative value declared as a gauge is summed rather than differenced by
 * anything dispatching on declared type, and SigNoz reported an {@code increase}
 * of 36 over a window in which the counter never moved off 4.
 */
@Component
public class CompensationMetrics implements MeterBinder {

    private final CompensationPublisher publisher;
    private final PaymentEventConsumer consumer;

    public CompensationMetrics(CompensationPublisher publisher, PaymentEventConsumer consumer) {
        this.publisher = publisher;
        this.consumer = consumer;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        // The rate to watch. Every one of these is a capture that succeeded at a
        // provider and that this ledger could not record - so a step change here
        // is a ledger problem, not a payments problem, however much it looks
        // like the latter from the merchant's side.
        FunctionCounter.builder("payorch.ledger.compensation_requested", publisher,
                        CompensationPublisher::requested)
                .description("Captures the ledger asked to have reversed")
                .register(registry);

        // Must be zero. A compensation that could not be published is money
        // captured, unaccounted for, and with nothing anywhere asking for it
        // back - the failure this phase exists to prevent, failing silently.
        FunctionCounter.builder("payorch.ledger.compensation_failed", publisher,
                        CompensationPublisher::failures)
                .description("Compensation requests that could not be published - must be zero")
                .register(registry);

        // NOT expected to be zero, unlike the one above. These are dead-lettered
        // captures whose ledger entries were already posted - a downstream
        // failure, not an accounting one - and each is a provider reversal the
        // guard correctly did not perform.
        FunctionCounter.builder("payorch.ledger.compensation_skipped", consumer,
                        PaymentEventConsumer::compensationsSkipped)
                .description("Dead-lettered captures already posted, so not compensated")
                .register(registry);
    }
}
