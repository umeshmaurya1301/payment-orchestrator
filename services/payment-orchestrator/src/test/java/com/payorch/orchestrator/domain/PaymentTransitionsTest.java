package com.payorch.orchestrator.domain;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transition table is the design. These tests are the design being asserted.
 */
class PaymentTransitionsTest {

    @Test
    void theHappyPathIsWalkable() {
        assertThat(PaymentTransitions.isAllowed(PaymentState.INITIATED, PaymentState.ROUTED)).isTrue();
        assertThat(PaymentTransitions.isAllowed(PaymentState.ROUTED, PaymentState.AUTHORIZING)).isTrue();
        assertThat(PaymentTransitions.isAllowed(PaymentState.AUTHORIZING, PaymentState.AUTHORIZED)).isTrue();
        assertThat(PaymentTransitions.isAllowed(PaymentState.AUTHORIZED, PaymentState.CAPTURED)).isTrue();
        assertThat(PaymentTransitions.isAllowed(PaymentState.CAPTURED, PaymentState.SETTLED)).isTrue();
    }

    /**
     * The fan-out that the whole state machine exists to express: an
     * authorization in flight has three possible ends, not two.
     */
    @Test
    void authorizingCanEndInThreeWays() {
        assertThat(PaymentTransitions.allowedFrom(PaymentState.AUTHORIZING))
                .containsExactlyInAnyOrder(
                        PaymentState.AUTHORIZED, PaymentState.FAILED, PaymentState.UNKNOWN);
    }

    /**
     * An UNKNOWN payment resolves only into what the provider says actually
     * happened. It must never go back to AUTHORIZING: retrying an authorization
     * whose outcome is unknown is the double charge this state exists to
     * prevent.
     */
    @Test
    void unknownResolvesButNeverRetries() {
        assertThat(PaymentTransitions.allowedFrom(PaymentState.UNKNOWN))
                .containsExactlyInAnyOrder(PaymentState.AUTHORIZED, PaymentState.FAILED);

        assertThat(PaymentTransitions.isAllowed(PaymentState.UNKNOWN, PaymentState.AUTHORIZING)).isFalse();
        assertThat(PaymentTransitions.isAllowed(PaymentState.UNKNOWN, PaymentState.ROUTED)).isFalse();
    }

    @Test
    void terminalStatesGoNowhere() {
        assertThat(PaymentState.SETTLED.isTerminal()).isTrue();
        assertThat(PaymentState.FAILED.isTerminal()).isTrue();
        assertThat(PaymentState.UNKNOWN.isTerminal())
                .as("UNKNOWN is unresolved, not terminal - phase 8's poller has to be able to move it")
                .isFalse();
    }

    /** No state may transition to itself; a re-entry is a bug, not a no-op. */
    @ParameterizedTest
    @EnumSource(PaymentState.class)
    void noStateTransitionsToItself(PaymentState state) {
        assertThat(PaymentTransitions.isAllowed(state, state)).isFalse();
    }

    /** Every state is reachable from INITIATED, or the enum has dead members. */
    @Test
    void everyStateIsReachable() {
        Set<PaymentState> reachable = new java.util.HashSet<>();
        java.util.Deque<PaymentState> queue = new java.util.ArrayDeque<>();
        queue.add(PaymentState.INITIATED);
        reachable.add(PaymentState.INITIATED);

        while (!queue.isEmpty()) {
            for (PaymentState next : PaymentTransitions.allowedFrom(queue.poll())) {
                if (reachable.add(next)) {
                    queue.add(next);
                }
            }
        }

        assertThat(reachable).containsExactlyInAnyOrder(PaymentState.values());
    }

    @Test
    void anIllegalTransitionThrowsAndSaysWhatWasAllowed() {
        assertThatThrownBy(() ->
                PaymentTransitions.check(PaymentState.INITIATED, PaymentState.AUTHORIZED))
                .isInstanceOf(PaymentTransitions.IllegalTransitionException.class)
                .hasMessageContaining("INITIATED -> AUTHORIZED")
                .hasMessageContaining("ROUTED");
    }

    /**
     * The enforcement point. If a payment could be assigned a state without
     * consulting the table, the table would be documentation rather than a
     * control.
     */
    @Test
    void thePaymentEntityRefusesAnIllegalMove() {
        Payment payment = Payment.initiate(
                java.util.UUID.randomUUID(), 1000, "INR", "tok_x", "424242", "4242", null);

        assertThatThrownBy(() -> payment.transitionTo(PaymentState.CAPTURED))
                .isInstanceOf(PaymentTransitions.IllegalTransitionException.class);

        assertThat(payment.getState()).isEqualTo(PaymentState.INITIATED);
    }
}
