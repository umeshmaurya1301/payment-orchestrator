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
     * happened, or into an admission that nobody would say. It must never go
     * back to AUTHORIZING: retrying an authorization whose outcome is unknown is
     * the double charge this state exists to prevent.
     *
     * <p>Phase 8a added the third edge. The assertion is deliberately written as
     * "these two real outcomes, plus giving up, and nothing that re-attempts" -
     * an exact-set assertion would have to be rewritten every time an outcome is
     * added, and rewriting it is exactly when somebody would quietly let
     * AUTHORIZING back in.
     */
    @Test
    void unknownResolvesButNeverRetries() {
        assertThat(PaymentTransitions.allowedFrom(PaymentState.UNKNOWN))
                .containsExactlyInAnyOrder(
                        PaymentState.AUTHORIZED, PaymentState.FAILED, PaymentState.UNRESOLVED);

        assertThat(PaymentTransitions.isAllowed(PaymentState.UNKNOWN, PaymentState.AUTHORIZING)).isFalse();
        assertThat(PaymentTransitions.isAllowed(PaymentState.UNKNOWN, PaymentState.ROUTED)).isFalse();
    }

    /**
     * Phase 8a. Giving up is a one-way door for the MACHINE and not for a
     * person.
     *
     * <p>There is no edge back to UNKNOWN, which is what makes "the poller gave
     * up" mean something - if anything automatic could undo it, it would not be
     * giving up, and the phase-8 trap about polling forever would be back.
     *
     * <p>But the two real outcomes stay reachable, because a human who telephones
     * the provider and finally learns what happened has to be able to record it.
     * A genuinely terminal state would mean the one person holding the answer is
     * the one the state machine refuses to take it from.
     */
    @Test
    void givingUpIsTerminalForThePollerAndNotForAPerson() {
        assertThat(PaymentTransitions.allowedFrom(PaymentState.UNRESOLVED))
                .as("a person may still record what the provider eventually said")
                .containsExactlyInAnyOrder(PaymentState.AUTHORIZED, PaymentState.FAILED);

        assertThat(PaymentTransitions.isAllowed(PaymentState.UNRESOLVED, PaymentState.UNKNOWN))
                .as("nothing automatic may put a given-up payment back in the polled population")
                .isFalse();
        assertThat(PaymentTransitions.isAllowed(PaymentState.UNRESOLVED, PaymentState.AUTHORIZING))
                .as("and re-attempting is still the double charge UNKNOWN exists to prevent")
                .isFalse();
    }

    /** Only a payment nobody could resolve may be given up on. */
    @Test
    void onlyAnUnknownPaymentCanBeGivenUpOn() {
        for (PaymentState from : PaymentState.values()) {
            if (from == PaymentState.UNKNOWN) {
                continue;
            }
            assertThat(PaymentTransitions.isAllowed(from, PaymentState.UNRESOLVED))
                    .as("%s must not be able to become UNRESOLVED", from)
                    .isFalse();
        }
    }

    /**
     * Phase 6k. A captured payment has two ends now, and the second one is the
     * saga: settled, or given back.
     */
    @Test
    void aCapturedPaymentCanBeSettledOrReversed() {
        assertThat(PaymentTransitions.allowedFrom(PaymentState.CAPTURED))
                .containsExactlyInAnyOrder(PaymentState.SETTLED, PaymentState.REVERSED);
    }

    /**
     * REVERSED is an END, and specifically not a way back to AUTHORIZED.
     *
     * <p>The authorization is gone once the capture is reversed - the provider
     * has released the hold, and there is nothing left to capture against. An
     * edge back to AUTHORIZED would read as a helpful retry and would be a
     * second charge on a cardholder who has already been refunded, which is the
     * same class of mistake the UNKNOWN row exists to prevent.
     */
    @Test
    void reversedIsTerminalAndNeverRecaptured() {
        assertThat(PaymentState.REVERSED.isTerminal()).isTrue();
        assertThat(PaymentTransitions.isAllowed(PaymentState.REVERSED, PaymentState.CAPTURED)).isFalse();
        assertThat(PaymentTransitions.isAllowed(PaymentState.REVERSED, PaymentState.AUTHORIZED)).isFalse();
        assertThat(PaymentTransitions.isAllowed(PaymentState.REVERSED, PaymentState.SETTLED)).isFalse();
    }

    /**
     * Only a CAPTURED payment can be reversed. An authorization that was never
     * captured expires at the provider by itself, and reversing one would be
     * undoing something nobody collected.
     */
    @Test
    void onlyACapturedPaymentCanBeReversed() {
        for (PaymentState from : PaymentState.values()) {
            if (from == PaymentState.CAPTURED) {
                continue;
            }
            assertThat(PaymentTransitions.isAllowed(from, PaymentState.REVERSED))
                    .as("%s must not be reversible", from)
                    .isFalse();
        }
    }

    /**
     * Phase 8a. UNRESOLVED is NOT terminal, and the asymmetry with UNKNOWN is
     * the design rather than an oversight.
     *
     * <p>Both are states a payment can sit in without being finished, and
     * {@code isTerminal} reports both as non-terminal. What separates them is
     * who is expected to act: UNKNOWN is the poller's problem, UNRESOLVED is a
     * person's.
     */
    @Test
    void neitherUnresolvedStateClaimsToBeFinished() {
        assertThat(PaymentState.UNKNOWN.isTerminal()).isFalse();
        assertThat(PaymentState.UNRESOLVED.isTerminal())
                .as("a payment a human can still resolve has not finished")
                .isFalse();
    }

    @Test
    void terminalStatesGoNowhere() {
        assertThat(PaymentState.SETTLED.isTerminal()).isTrue();
        assertThat(PaymentState.FAILED.isTerminal()).isTrue();
        assertThat(PaymentState.REVERSED.isTerminal()).isTrue();
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
                .containsExactlyInAnyOrder(
                        PaymentState.AUTHORIZED, PaymentState.FAILED, PaymentState.UNRESOLVED)
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
