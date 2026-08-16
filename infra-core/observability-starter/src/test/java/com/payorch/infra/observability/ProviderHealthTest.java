package com.payorch.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProviderHealthTest {

    private static final long TARGET = 500;

    private static ProviderHealth score(double successRate, long p99, int breaker,
                                        double freePermits, long samples) {
        return ProviderHealth.score("psp-x", successRate, p99, breaker, freePermits, samples, TARGET);
    }

    @Test
    @DisplayName("a provider doing everything it promised scores near 100")
    void healthyScoresHigh() {
        ProviderHealth h = score(1.0, 200, 0, 1.0, 500);

        assertThat(h.score()).isGreaterThanOrEqualTo(95);
        assertThat(h.reason()).isEqualTo("healthy");
        assertThat(h.routable()).isTrue();
    }

    @Test
    @DisplayName("slow-but-succeeding and fast-but-failing do NOT score the same")
    void theTwoDegradationsAreDistinguished() {
        // The phase-5 requirement, and the reason the terms multiply rather than
        // being summed. A weighted sum would give these two the same number.
        ProviderHealth slow = score(1.00, TARGET * 3, 0, 1.0, 500);
        ProviderHealth failing = score(0.50, TARGET, 0, 1.0, 500);

        assertThat(slow.score()).isNotEqualTo(failing.score());
        assertThat(slow.reason()).isEqualTo("slow");
        assertThat(failing.reason()).isEqualTo("failing calls");
    }

    @Test
    @DisplayName("failing is punished harder than slow, at comparable magnitude")
    void failingIsWorseThanSlow() {
        ProviderHealth slow = score(1.00, TARGET * 3, 0, 1.0, 500);
        ProviderHealth failing = score(0.50, TARGET, 0, 1.0, 500);

        // A lost sale beats an annoyed customer. If a later tweak inverts this,
        // routing would prefer a provider that declines half its payments over
        // one that is merely three times slower - and it would do it silently.
        assertThat(failing.score()).isLessThan(slow.score());
    }

    @Test
    @DisplayName("both problems at once scores worse than either alone")
    void degradationsCompound() {
        ProviderHealth slow = score(1.00, TARGET * 3, 0, 1.0, 500);
        ProviderHealth failing = score(0.50, TARGET, 0, 1.0, 500);
        ProviderHealth both = score(0.50, TARGET * 3, 0, 1.0, 500);

        assertThat(both.score()).isLessThan(slow.score());
        assertThat(both.score()).isLessThan(failing.score());
    }

    @Test
    @DisplayName("an open breaker is unroutable, whatever the other signals say")
    void openBreakerIsUnroutable() {
        // Deliberately perfect on every other axis: the breaker is a gate, not
        // a term, because calls are not reaching the provider at all.
        ProviderHealth h = score(1.0, 10, 1, 1.0, 5_000);

        assertThat(h.score()).isZero();
        assertThat(h.routable()).isFalse();
        assertThat(h.reason()).isEqualTo("breaker open");
    }

    @Test
    @DisplayName("a half-open breaker is capped, so recovery does not summon a thundering herd")
    void halfOpenIsCapped() {
        ProviderHealth h = score(1.0, 100, 2, 1.0, 500);

        assertThat(h.score()).isLessThanOrEqualTo(12);
        assertThat(h.routable()).isTrue();      // eligible for a trickle, not for everything
        assertThat(h.reason()).contains("half-open");
    }

    @Test
    @DisplayName("no traffic means neutral, not perfect and not broken")
    void emptyWindowIsNeutral() {
        // The stale-health trap: a provider with no traffic generates no signal.
        // Scoring it 100 would send it everything; scoring it 0 would mean it
        // never gets a call again and so never recovers.
        ProviderHealth h = score(-1, -1, 0, 1.0, 0);

        assertThat(h.score()).isEqualTo(ProviderHealth.NEUTRAL);
        assertThat(h.routable()).isTrue();
        assertThat(h.reason()).contains("no recent calls");
    }

    @Test
    @DisplayName("a saturated bulkhead lowers the score without declaring the provider dead")
    void saturationReducesButDoesNotZero() {
        ProviderHealth full = score(1.0, 200, 0, 1.0, 500);
        ProviderHealth saturated = score(1.0, 200, 0, 0.0, 500);

        assertThat(saturated.score()).isLessThan(full.score());
        // Still routable: saturation is a reason to send less, not a reason to
        // stop - and it may be the only provider left.
        assertThat(saturated.routable()).isTrue();
        assertThat(saturated.reason()).isEqualTo("saturated");
    }

    @Test
    @DisplayName("a provider failing every call is unroutable")
    void totalFailureIsUnroutable() {
        ProviderHealth h = score(0.0, 200, 0, 1.0, 500);

        assertThat(h.score()).isZero();
        assertThat(h.routable()).isFalse();
    }
}
