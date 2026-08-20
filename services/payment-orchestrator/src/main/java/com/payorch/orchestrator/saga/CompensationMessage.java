package com.payorch.orchestrator.saga;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A request from the ledger to undo a capture. Phase 6k.
 *
 * <p>Its own type, not the ledger&#39;s {@code CompensationRequest}, for the same
 * reason {@code PaymentEventMessage} is not the orchestrator&#39;s
 * {@code PaymentEvent}: a class shared across a Kafka boundary makes the
 * producer&#39;s field list a compile-time dependency of the consumer, and adding a
 * field becomes a coordinated deploy.
 *
 * <p>Note the direction. The ledger is downstream of this service on
 * {@code payment.events} and upstream of it here, which is what a choreographed
 * saga looks like from inside: there is no coordinator, only services that
 * publish what happened and services that decide what to do about it.
 *
 * <p>{@code ignoreUnknown} because the ledger may add fields at any time, and a
 * consumer that fails on an unrecognised one turns a routine producer change
 * into a queue full of poison messages.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompensationMessage(
        UUID paymentId,
        UUID eventId,
        UUID merchantId,
        long amountMinor,
        String currency,
        String reason,
        Instant requestedAt) {
}
