package com.payorch.orchestrator.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The double-charge rule, asserted directly.
 *
 * <p>These are the highest-consequence tests in the project. Every case below
 * corresponds to a way a customer's card could be charged twice if the
 * classification were wrong.
 */
class FailoverPolicyTest {

    /**
     * Every error code this system records on a path that leaves the payment
     * {@code UNKNOWN} - kept as a literal list rather than derived, so that the
     * test fails if a new ambiguous code is added and forgotten here.
     */
    private static final Set<String> AMBIGUOUS = Set.of(
            "connector_unavailable",
            "deadline_abandoned");

    @ParameterizedTest
    @ValueSource(strings = {"circuit_open", "deadline_exceeded", "rate_limited", "bulkhead_full"})
    @DisplayName("errors that prove nothing was sent may fail over")
    void unambiguousFailuresMayFailOver(String errorCode) {
        assertThat(FailoverPolicy.mayFailOver(errorCode)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"connector_unavailable", "deadline_abandoned"})
    @DisplayName("ambiguous errors must NEVER fail over - this is the double charge")
    void ambiguousFailuresMayNotFailOver(String errorCode) {
        // The request went out and no answer came back. The card may already be
        // charged. A second provider has never heard of the first one's
        // idempotency reference and will authorize it again.
        assertThat(FailoverPolicy.mayFailOver(errorCode)).isFalse();
    }

    @Test
    @DisplayName("the ambiguous and nothing-was-sent lists cannot overlap")
    void theTwoListsAreDisjoint() {
        // Belt and braces against the two sets drifting into agreement as codes
        // are added. If this ever fails, an ambiguous failure has become
        // eligible for failover, which is the exact bug this class exists to
        // prevent.
        assertThat(FailoverPolicy.nothingWasSentCodes()).doesNotContainAnyElementsOf(AMBIGUOUS);
    }

    @Test
    @DisplayName("a decline never fails over")
    void declinesAreNotFailedOver() {
        // A decline is a definite answer from a healthy provider, not a fault.
        // Shopping it around until someone says yes is how a system earns a
        // card-issuer fraud flag - and it is not in the allowlist, so it is
        // refused by default rather than by a special case.
        assertThat(FailoverPolicy.mayFailOver("do_not_honour")).isFalse();
        assertThat(FailoverPolicy.mayFailOver("insufficient_funds")).isFalse();
    }

    @Test
    @DisplayName("an unrecognised error code fails closed")
    void unknownCodesFailClosed() {
        // The allowlist's whole point: a code nobody has classified is not
        // eligible. Being wrong in this direction costs one avoidable decline;
        // being wrong in the other direction costs a customer twice the money.
        assertThat(FailoverPolicy.mayFailOver("something_new")).isFalse();
        assertThat(FailoverPolicy.mayFailOver(null)).isFalse();
        assertThat(FailoverPolicy.mayFailOver("")).isFalse();
    }
}
