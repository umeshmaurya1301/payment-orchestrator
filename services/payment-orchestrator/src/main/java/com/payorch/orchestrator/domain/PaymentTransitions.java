package com.payorch.orchestrator.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static com.payorch.orchestrator.domain.PaymentState.AUTHORIZED;
import static com.payorch.orchestrator.domain.PaymentState.AUTHORIZING;
import static com.payorch.orchestrator.domain.PaymentState.CAPTURED;
import static com.payorch.orchestrator.domain.PaymentState.FAILED;
import static com.payorch.orchestrator.domain.PaymentState.INITIATED;
import static com.payorch.orchestrator.domain.PaymentState.ROUTED;
import static com.payorch.orchestrator.domain.PaymentState.SETTLED;
import static com.payorch.orchestrator.domain.PaymentState.UNKNOWN;

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
 *       the double charge this state exists to prevent.</li>
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
        table.put(AUTHORIZING, Set.of(AUTHORIZED, FAILED, UNKNOWN));

        table.put(AUTHORIZED, Set.of(CAPTURED, FAILED));
        table.put(CAPTURED, Set.of(SETTLED));

        // Resolution only. Phase 8's status poller asks the provider what
        // happened and moves the payment to whichever of these it says.
        table.put(UNKNOWN, Set.of(AUTHORIZED, FAILED));

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
