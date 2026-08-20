package com.payorch.orchestrator.domain;

/**
 * Every state a payment can be in.
 *
 * <pre>
 * INITIATED → ROUTED → AUTHORIZING → AUTHORIZED → CAPTURED → SETTLED
 *                           ↓                                ↓
 *                     FAILED / UNKNOWN                    REVERSED
 * </pre>
 *
 * <p>A plain enum with an explicit transition table beside it - see
 * {@link PaymentTransitions} - rather than a state machine library. The rules
 * are the interesting part of this design, and a library turns them into
 * configuration nobody reads. Being able to print the transition table as data,
 * and to point at the one row that surprises people, is worth more than the
 * framework.
 */
public enum PaymentState {

    /** Accepted and persisted. No provider has been chosen. */
    INITIATED,

    /** A provider has been selected. Nothing has been sent to it. */
    ROUTED,

    /** An authorization request is in flight. The outcome is not yet known. */
    AUTHORIZING,

    /** The provider approved. Funds are held, not taken. */
    AUTHORIZED,

    /** The provider captured. Funds are taken. */
    CAPTURED,

    /**
     * The capture was given back. Phase 6k's compensating action.
     *
     * <p><strong>Not {@code FAILED}, and the difference is not cosmetic.</strong>
     * A failed payment never moved money: the customer's statement has nothing
     * on it and the merchant was never owed. A reversed payment took the money
     * and returned it, so there are two entries on that statement and a customer
     * who may well ring up about them. Collapsing the two would make the
     * orchestrator's state disagree with the only record the customer can see,
     * and every support conversation about a reversal would start from a lie.
     *
     * <p>Terminal, deliberately. There is no {@code REVERSED → AUTHORIZED} and no
     * re-capture: the authorization was consumed by the capture that was undone,
     * and a system that could quietly re-take money it had just given back is one
     * bug away from doing it in a loop. A merchant who still wants the money
     * creates a new payment, with a new authorization, which the customer's bank
     * gets to decide on again - which is the correct place for that decision.
     */
    REVERSED,

    /** Settlement confirmed by the provider's report. */
    SETTLED,

    /** A definitive negative answer: a decline, or a rejection before any call. */
    FAILED,

    /**
     * <strong>The state this whole design exists for.</strong>
     *
     * <p>A connector timeout does not mean the payment failed. It means the
     * outcome is not known: the provider may have authorized the card and lost
     * the response on the way back. Treating that as {@code FAILED} is how
     * home-built payment systems double-charge people - the caller retries, the
     * provider authorizes a second time, and two holds sit on one card.
     *
     * <p>Modelling it now rather than later is not a preference. Retrofitting a
     * state means revisiting every transition, every query that filters on
     * state, every report that assumed two outcomes, and every row already
     * written under the old assumption.
     *
     * <p>A background poller in phase 8 resolves these into a terminal state by
     * asking the provider what it did with the reference.
     */
    UNKNOWN;

    /** No further transition is possible from here. */
    public boolean isTerminal() {
        return PaymentTransitions.allowedFrom(this).isEmpty();
    }
}
