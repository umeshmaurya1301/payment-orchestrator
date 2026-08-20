package com.payorch.ledger.saga;

import java.time.Instant;
import java.util.UUID;

/**
 * A request to undo something this service could not record. Phase 6k.
 *
 * <h2>A request, not a command and not an event</h2>
 *
 * <p>The distinction is the whole design. The ledger does not reverse anything -
 * it cannot, it has no provider credentials and no business making a payment
 * call - and it does not announce a fact, because nothing has happened yet. It
 * says "I could not account for this capture; whoever owns it should consider
 * undoing it", and the orchestrator decides. That is what keeps the saga
 * choreographed: no service is telling another service what to do, and the
 * orchestrator is free to answer NOT_CAPTURED and do nothing at all.
 *
 * <h2>Why the event id is carried</h2>
 *
 * <p>So the two halves of the fix can be told apart afterwards. The
 * orchestrator&#39;s reversal and the original dead-lettered capture are separate
 * records in separate stores, and the only thing linking them is this id
 * travelling through the compensation. Without it, "which capture was this
 * reversal for" is answerable only by timestamp correlation.
 *
 * <p>Deliberately its own record rather than a reused {@code PaymentEventMessage}
 * for the reason in that class: a type shared across a Kafka boundary makes the
 * producer&#39;s field list a compile-time dependency of the consumer. These are
 * different messages on a different topic with a different meaning, and the
 * resemblance is not a reason to couple them.
 */
public record CompensationRequest(
        UUID paymentId,
        UUID eventId,
        UUID merchantId,
        long amountMinor,
        String currency,

        /**
         * Why the compensation is being asked for, from a small closed set this
         * system defines. Not free text, and never anything from a payload -
         * see {@code LogFields.COMPENSATION_REASON}.
         */
        String reason,

        Instant requestedAt) {

    /** The only reason that exists today. Named rather than inlined so the set stays visible. */
    public static final String LEDGER_DEAD_LETTERED = "ledger_dead_lettered";
}
