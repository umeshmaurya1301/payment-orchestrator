package com.payorch.orchestrator.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static com.payorch.orchestrator.domain.PaymentState.AUTHORIZED;
import static com.payorch.orchestrator.domain.PaymentState.AUTHORIZING;
import static com.payorch.orchestrator.domain.PaymentState.CAPTURED;
import static com.payorch.orchestrator.domain.PaymentState.FAILED;
import static com.payorch.orchestrator.domain.PaymentState.INITIATED;
import static com.payorch.orchestrator.domain.PaymentState.REVERSED;
import static com.payorch.orchestrator.domain.PaymentState.ROUTED;
import static com.payorch.orchestrator.domain.PaymentState.SETTLED;
import static com.payorch.orchestrator.domain.PaymentState.UNKNOWN;
import static com.payorch.orchestrator.domain.PaymentState.UNRESOLVED;

/**
 * The transition rules, as data.
 *
 * <p>Written as a table rather than scattered across {@code if} statements in a
 * service, because a table can be read in one sitting, printed in a design
 * discussion, and tested exhaustively - and because the interesting claims about
 * this system are all statements about this table.
 *
 * <p>Two rows are worth arguing about:
 *
 * <ul>
 *   <li>{@code AUTHORIZING → UNKNOWN}. A timeout is not a failure. See
 *       {@link PaymentState#UNKNOWN}.</li>
 *   <li>{@code UNKNOWN → AUTHORIZED} and {@code UNKNOWN → FAILED}, and nothing
 *       else. An unknown payment resolves only into what the provider says
 *       actually happened. It cannot be retried into {@code AUTHORIZING},
 *       because retrying an authorization whose outcome is unknown is exactly
 *       the double charge this state exists to prevent. Phase 5's failover
 *       leans on this row: it is what makes failing over after an ambiguous
 *       timeout impossible rather than merely forbidden.</li>
 *   <li>{@code AUTHORIZING → ROUTED}, added in phase 5. A failover to a second
 *       provider, reachable only when the first provably never received the
 *       request. See the comment on that row.</li>
 * </ul>
 */
public final class PaymentTransitions {

    private static final Map<PaymentState, Set<PaymentState>> ALLOWED = allowedTransitions();

    private PaymentTransitions() {
    }

    private static Map<PaymentState, Set<PaymentState>> allowedTransitions() {
        Map<PaymentState, Set<PaymentState>> table = new EnumMap<>(PaymentState.class);

        // Rejected before any provider was chosen - an unroutable currency, no
        // enabled provider - is still a FAILED payment, not an error.
        table.put(INITIATED, Set.of(ROUTED, FAILED));

        table.put(ROUTED, Set.of(AUTHORIZING, FAILED));

        // The only fan-out in the machine, and the reason the machine exists.
        //
        // ROUTED is the phase-5 addition: a failover. The payment was being
        // authorized on one provider, that provider PROVABLY never received the
        // request, and it is now being routed to another.
        //
        // This is the one transition in the table that could enable a double
        // charge if it were reachable from the wrong place, so note what makes
        // it safe - and note that the safety does not depend on
        // FailoverPolicy being correct:
        //
        //   * A request that was SENT and not answered goes AUTHORIZING →
        //     UNKNOWN, and UNKNOWN's row below has no way back to ROUTED or
        //     AUTHORIZING. So the ambiguous case cannot reach a second provider
        //     even if some future caller tries to make it.
        //   * A request that was never sent leaves the card untouched, so the
        //     second provider is authorizing a payment for the first time. It
        //     needs no idempotency cooperation, which is just as well: an
        //     idempotency key is only meaningful inside the namespace of the
        //     provider that issued it.
        //
        // Two independent controls therefore have to fail together for a
        // customer to be charged twice: FailoverPolicy must misclassify an
        // ambiguous error as safe, AND this table must permit the transition
        // that follows. That redundancy is deliberate.
        table.put(AUTHORIZING, Set.of(AUTHORIZED, FAILED, UNKNOWN, ROUTED));

        table.put(AUTHORIZED, Set.of(CAPTURED, FAILED));

        // Phase 6k. The compensating edge, and the only one in the table that
        // exists to undo something rather than to advance it.
        //
        // Reachable ONLY from CAPTURED, which is what makes the compensation
        // honest: a reversal claims money moved and was returned, so it must be
        // impossible to reach from a state where money never moved. An
        // AUTHORIZED payment that goes wrong has nothing to give back - the hold
        // expires on its own - and letting it reach REVERSED would put a
        // reversal in the ledger for a capture that never happened.
        //
        // There is no edge back. See PaymentState.REVERSED.
        table.put(CAPTURED, Set.of(SETTLED, REVERSED));
        table.put(REVERSED, Set.of());

        // Resolution only. Phase 8's status poller asks the provider what
        // happened and moves the payment to whichever of these it says.
        // Phase 8a adds the third edge. The poller resolves an UNKNOWN into
        // what the provider says happened, or - having asked as often as it is
        // allowed to - gives up and says so out loud.
        table.put(UNKNOWN, Set.of(AUTHORIZED, FAILED, UNRESOLVED));

        // UNRESOLVED keeps UNKNOWN's two real outcomes and NOT the edge back to
        // UNKNOWN.
        //
        // Keeping them, because the poller giving up does not make the answer
        // unknowable - it makes it unavailable to a machine. A human who rings
        // the provider and finally learns what happened has to be able to record
        // it, and a genuinely terminal state would mean the one person holding
        // the answer is the one the state machine refuses to take it from.
        //
        // Not the edge back, because that is the loop the phase-8 trap warns
        // about: "a status poller without a give-up state - payments stuck in
        // UNKNOWN forever, polled forever". If giving up could be undone by
        // anything automatic, it is not giving up. The poller selects UNKNOWN
        // and never UNRESOLVED, so the only way back into the polled population
        // is a person deciding to put it there.
        table.put(UNRESOLVED, Set.of(AUTHORIZED, FAILED));

        table.put(SETTLED, Set.of());
        table.put(FAILED, Set.of());

        return Map.copyOf(table);
    }

    public static Set<PaymentState> allowedFrom(PaymentState state) {
        return ALLOWED.getOrDefault(state, Set.of());
    }

    public static boolean isAllowed(PaymentState from, PaymentState to) {
        return allowedFrom(from).contains(to);
    }

    /**
     * @throws IllegalTransitionException always, when the transition is not in
     *         the table. Deliberately fatal rather than logged: an illegal
     *         transition means the code's model of the payment has diverged
     *         from the database's, and continuing past that point is how a
     *         payment ends up captured twice or refunded for an amount it never
     *         held.
     */
    public static void check(PaymentState from, PaymentState to) {
        if (!isAllowed(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
    }

    /** An attempt to move a payment along an edge that does not exist. */
    public static class IllegalTransitionException extends RuntimeException {

        private final PaymentState from;
        private final PaymentState to;

        public IllegalTransitionException(PaymentState from, PaymentState to) {
            super("illegal payment transition " + from + " -> " + to
                    + "; allowed from " + from + ": " + allowedFrom(from));
            this.from = from;
            this.to = to;
        }

        public PaymentState from() {
            return from;
        }

        public PaymentState to() {
            return to;
        }
    }
}
