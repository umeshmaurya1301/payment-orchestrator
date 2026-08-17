package com.payorch.ledger.consume;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The consumer's view of a payment event.
 *
 * <p><strong>Deliberately not a shared class with the orchestrator's
 * {@code PaymentEvent}.</strong> A shared type across a Kafka boundary makes the
 * producer's field list a compile-time dependency of every consumer, so adding a
 * field becomes a coordinated deploy - which is most of what an event-driven
 * architecture is supposed to avoid.
 *
 * <p>Note the import: Jackson 3 moved databind to {@code tools.jackson.databind}
 * but left the ANNOTATIONS in {@code com.fasterxml.jackson.annotation}, which
 * are shared between both major versions. Reaching for {@code tools.jackson.annotation}
 * because everything else in this project is Jackson 3 does not compile.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is the other half of
 * that: the producer may add fields at any time, and a consumer that fails on an
 * unrecognised one turns a routine producer change into a DLQ full of poison
 * messages.
 *
 * <p>Note what is absent: no PAN, no CVV, no expiry. The token is a vault
 * reference and the BIN and last four are the same digits phase 1 already stores
 * in plain text. Nothing here would be a leak in a DLQ.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentEventMessage(
        UUID eventId,
        UUID paymentId,
        UUID merchantId,
        String type,
        String state,
        long amountMinor,
        String currency,
        String pspId,
        String cardToken,
        String cardBin,
        String cardLast4,
        Instant occurredAt) {
}
