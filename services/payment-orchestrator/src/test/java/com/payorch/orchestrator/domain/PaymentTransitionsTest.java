package com.payorch.orchestrator.domain;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
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
     * authorization in flight has three possible ENDS, not two.
     *
     * <p>Phase 5 added a fourth edge that is not an end - {@code ROUTED}, the
     * failover - so this asserts the terminal set explicitly rather than the
     * whole row. The distinction matters: three ways for a payment to be over,
     * plus one way for it to continue somewhere else.
     */
    @Test
    void authorizingCanEndInThreeWays() {
        assertThat(PaymentTransitions.allowedFrom(PaymentState.AUTHORIZING))
                .contains(PaymentState.AUTHORIZED, PaymentState.FAILED, PaymentState.UNKNOWN)
                .hasSize(4);

        // ...and the fourth is the failover, which is emphatically not an end.
        assertThat(PaymentTransitions.allowedFrom(PaymentState.AUTHORIZING))
                .contains(PaymentState.ROUTED);
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

    @Test
    @DisplayName("an UNKNOWN payment can never be routed or authorized again")
    void unknownIsNeverReAuthorized() {
        // The single most important row in the table, and phase 5's failover
        // safety net. An UNKNOWN payment may have been charged. Sending it to
        // another provider - or the same one - would be the double charge the
        // state exists to prevent, so the machine refuses it structurally
        // rather than trusting every caller to remember.
        assertThat(PaymentTransitions.allowedFrom(PaymentState.UNKNOWN))
                .containsExactlyInAnyOrder(PaymentState.AUTHORIZED, PaymentState.FAILED)
                .doesNotContain(PaymentState.ROUTED, PaymentState.AUTHORIZING);
    }

    @Test
    @DisplayName("AUTHORIZING may return to ROUTED, which is how failover works")
    void authorizingMayBeReRouted() {
        // Phase 5. Reachable only when the provider provably never received the
        // request; the ambiguous path has already gone to UNKNOWN by the time
        // this would be attempted, and the assertion above closes that door.
        assertThat(PaymentTransitions.allowedFrom(PaymentState.AUTHORIZING))
                .contains(PaymentState.ROUTED);
    }

    @Test
    @DisplayName("FAILED stays terminal even with failover in the picture")
    void failedIsStillTerminal() {
        // A merchant has been told the payment failed. Reviving it afterwards
        // would authorize a card for a payment its owner believes is over -
        // which is why failover records the attempt failure WITHOUT moving the
        // payment to FAILED, and only closes it once no providers remain.
        assertThat(PaymentTransitions.allowedFrom(PaymentState.FAILED)).isEmpty();
    }
}
