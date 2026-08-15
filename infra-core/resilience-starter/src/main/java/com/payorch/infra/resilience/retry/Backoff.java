package com.payorch.infra.resilience.retry;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with <strong>full</strong> jitter.
 *
 * <pre>{@code
 * delay = random(0, min(maxDelay, base * 2^attempt))
 * }</pre>
 *
 * <p>The jitter strategy is a real choice with a measurable difference, not a
 * detail. Four candidates, and why this one:
 *
 * <table>
 *   <tr><td><b>None</b></td>
 *       <td>Every client that failed at the same instant retries at the same
 *           instant. The retry wave is as synchronised as the outage that
 *           caused it, and it arrives in a spike the downstream is least able
 *           to absorb.</td></tr>
 *   <tr><td><b>Equal jitter</b><br>{@code d/2 + random(0, d/2)}</td>
 *       <td>Halves the spike but keeps a hard floor, so retries still cluster
 *           in the back half of each window.</td></tr>
 *   <tr><td><b>Decorrelated</b></td>
 *       <td>Spreads well, but the delay depends on the previous delay, so it
 *           has memory and is harder to reason about and to test.</td></tr>
 *   <tr><td><b>Full jitter</b> ✓</td>
 *       <td>Spreads retries uniformly across the whole window. Lowest expected
 *           contention, and the simplest to state.</td></tr>
 * </table>
 *
 * <p>The cost of full jitter is that an individual retry can fire almost
 * immediately, which feels wrong until you notice that the goal is the
 * behaviour of the <em>population</em> of clients, not of any one of them. AWS's
 * "Exponential Backoff and Jitter" measured this: full jitter completes the same
 * work in fewer total calls than equal jitter, because it wastes less of the
 * downstream's capacity on synchronised collisions.
 */
public final class Backoff {

    private final long baseDelayMs;
    private final long maxDelayMs;

    public Backoff(long baseDelayMs, long maxDelayMs) {
        this.baseDelayMs = Math.max(baseDelayMs, 1);
        this.maxDelayMs = Math.max(maxDelayMs, this.baseDelayMs);
    }

    /**
     * @param attempt zero-based retry number: 0 is the first retry
     * @return a delay drawn uniformly from {@code [0, ceiling]}
     */
    public long delayMs(int attempt) {
        long ceiling = ceilingMs(attempt);
        // Bound is exclusive, hence +1, so the full ceiling is reachable.
        return ThreadLocalRandom.current().nextLong(ceiling + 1);
    }

    /** The exponential ceiling for an attempt, before jitter. Exposed for tests and for logs. */
    public long ceilingMs(int attempt) {
        if (attempt < 0) {
            return baseDelayMs;
        }
        // Shift rather than Math.pow, and capped at 32 so a large attempt count
        // cannot overflow into a negative ceiling - which would make
        // nextLong(bound) throw and turn a retry into a crash.
        int shift = Math.min(attempt, 32);
        long scaled = baseDelayMs << shift;
        if (scaled < 0) {
            return maxDelayMs;
        }
        return Math.min(scaled, maxDelayMs);
    }
}
