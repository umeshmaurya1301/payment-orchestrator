package com.payorch.infra.resilience.retry;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffTest {

    private final Backoff backoff = new Backoff(100, 2_000);

    @Test
    void theCeilingDoublesUntilItIsCapped() {
        assertThat(backoff.ceilingMs(0)).isEqualTo(100);
        assertThat(backoff.ceilingMs(1)).isEqualTo(200);
        assertThat(backoff.ceilingMs(2)).isEqualTo(400);
        assertThat(backoff.ceilingMs(3)).isEqualTo(800);
        assertThat(backoff.ceilingMs(4)).isEqualTo(1_600);
        assertThat(backoff.ceilingMs(5)).isEqualTo(2_000);
        assertThat(backoff.ceilingMs(50)).isEqualTo(2_000);
    }

    /**
     * A large attempt count must not overflow the shift into a negative
     * ceiling. It would surface as {@code nextLong(bound)} throwing, which
     * turns a retry into a crash - the one outcome worse than not retrying.
     */
    @Test
    void aHugeAttemptCountDoesNotOverflow() {
        assertThat(backoff.ceilingMs(Integer.MAX_VALUE)).isEqualTo(2_000);
        assertThat(backoff.delayMs(Integer.MAX_VALUE)).isBetween(0L, 2_000L);
    }

    @Test
    void delaysStayWithinTheCeiling() {
        for (int attempt = 0; attempt < 8; attempt++) {
            long ceiling = backoff.ceilingMs(attempt);
            for (int i = 0; i < 200; i++) {
                assertThat(backoff.delayMs(attempt)).isBetween(0L, ceiling);
            }
        }
    }

    /**
     * <strong>Full</strong> jitter, not equal jitter. The distinguishing
     * property is that the whole window is reachable, including values near
     * zero - which is what spreads a population of clients uniformly instead of
     * clustering them in the back half of each window.
     */
    @Test
    void theWholeWindowIsUsed() {
        Set<Long> buckets = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            // attempt 3 -> ceiling 800ms; bucket into eighths
            buckets.add(backoff.delayMs(3) / 100);
        }

        assertThat(buckets)
                .as("equal jitter would never produce the low buckets")
                .hasSizeGreaterThanOrEqualTo(8);
        assertThat(buckets).contains(0L);
    }

    @Test
    void aZeroOrNegativeBaseIsClampedRatherThanExploding() {
        Backoff degenerate = new Backoff(0, 0);

        assertThat(degenerate.ceilingMs(0)).isEqualTo(1);
        assertThat(degenerate.delayMs(0)).isBetween(0L, 1L);
    }
}
