package com.payorch.orchestrator.events;

/**
 * Where a terminal payment outcome goes next.
 *
 * <p>An interface with two implementations on purpose, because phase 6 is a
 * comparison rather than a build: {@link DirectKafkaPublisher} is the naive
 * dual-write that the outbox exists to replace, and it is kept rather than
 * deleted so the "before" arm can be re-run at any time.
 *
 * <p>Selected by {@code payorch.events.publisher}, defaulting to the safe one.
 */
public interface PaymentEventPublisher {

    void publish(PaymentEvent event);
}
