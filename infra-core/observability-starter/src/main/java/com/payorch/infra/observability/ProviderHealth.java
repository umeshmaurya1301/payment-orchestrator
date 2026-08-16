package com.payorch.infra.observability;

/**
 * One provider's health, as a number a router can rank on.
 *
 * <p>Phase 5's centrepiece: the circuit breaker's state stops being a terminal
 * outcome and becomes an <em>input to a decision</em>. A breaker that opens
 * fails calls fast; a health score that drops drains traffic gracefully, and the
 * difference is what a merchant experiences.
 *
 * @param pspId       the provider
 * @param score       0-100. 0 is unroutable, 100 is a provider doing everything
 *                    it promised.
 * @param successRate rolling success rate, or -1 when the window is empty
 * @param p99Ms       rolling P99 in milliseconds, or -1 when the window is empty
 * @param breakerState 0 closed, 1 open, 2 half-open
 * @param freePermits fraction of the bulkhead's permits still available, 0-1
 * @param samples     calls in the window - how much this score is worth
 * @param reason      the dominant term, for the operator staring at a dashboard
 *                    wondering why a provider is being avoided
 */
public record ProviderHealth(
        String pspId,
        int score,
        double successRate,
        long p99Ms,
        int breakerState,
        double freePermits,
        long samples,
        String reason) {

    /** Below this a provider is skipped entirely rather than merely deprioritised. */
    public static final int UNROUTABLE = 10;

    /**
     * Where a provider with no recent traffic sits: neutral, not perfect.
     *
     * <p>This is the answer to the phase-5 trap that a provider receiving no
     * traffic generates no signal, so its health never changes and it never gets
     * traffic again. Scoring an empty window as 100 would send every payment to
     * whichever provider was most recently ignored; scoring it 0 would make the
     * first failure permanent. Neutral means a rested provider is tried, and one
     * probe's worth of evidence immediately replaces the guess.
     */
    public static final int NEUTRAL = 50;

    public boolean routable() {
        return score >= UNROUTABLE;
    }

    /**
     * Scores a provider from the four signals phase 5 names.
     *
     * <h2>The weighting, and why it is multiplicative</h2>
     *
     * <p>The phase plan is explicit that "a provider that is slow but succeeding
     * is a different problem from one that is fast but failing, and they should
     * not produce the same score". A weighted sum produces exactly that
     * collision - 50% availability plus 100% speed scores the same as 100%
     * availability plus 50% speed - so the terms multiply instead. A provider
     * that is both slow and failing then scores worse than either alone, which
     * is the truth about it.
     *
     * <p><strong>Failing counts for more than slow.</strong> The success term is
     * cubed and the latency term is not. For a payment, a slow authorization is
     * an annoyed customer; a failed one is a lost sale, and a wrongly failed one
     * may be a double charge later. The two degradations at comparable
     * magnitude, both at full capacity:
     *
     * <pre>
     *   slow but succeeding   1.00^3 x (1/3)  = 0.33  -> 33
     *   fast but failing      0.50^3 x 1.00   = 0.125 -> 12
     * </pre>
     *
     * <p>Three times the target latency is survivable; half the calls failing is
     * not, and the score says so. That ordering is the whole point of the
     * exponent, and a test asserts it so a later tweak cannot quietly invert it.
     *
     * <h2>The breaker is a gate, not a term</h2>
     *
     * <p>An open breaker means calls are not reaching the provider at all, so
     * there is nothing to weigh: the score is 0 and the provider is skipped.
     * Half-open is capped rather than zeroed, which is the phase-5 answer to the
     * thundering herd on recovery - a recovering provider becomes eligible for a
     * trickle of traffic rather than for all of it the instant it half-opens.
     */
    public static ProviderHealth score(String pspId,
                                       double successRate,
                                       long p99Ms,
                                       int breakerState,
                                       double freePermits,
                                       long samples,
                                       long targetP99Ms) {

        if (breakerState == 1) {
            return new ProviderHealth(pspId, 0, successRate, p99Ms, breakerState,
                    freePermits, samples, "breaker open");
        }

        // No evidence: sit at neutral rather than inventing a verdict. See NEUTRAL.
        if (samples == 0 || successRate < 0) {
            int score = breakerState == 2 ? Math.min(NEUTRAL, HALF_OPEN_CAP) : NEUTRAL;
            return new ProviderHealth(pspId, score, successRate, p99Ms, breakerState,
                    freePermits, samples, "no recent calls - neutral");
        }

        double availability = successRate * successRate * successRate;

        // Latency term: 1.0 at or under target, decaying as the ratio grows.
        // Never zero, because a provider that is merely slow is still a provider
        // and may be the only one left.
        double latency = 1.0;
        if (p99Ms > 0 && targetP99Ms > 0 && p99Ms > targetP99Ms) {
            latency = (double) targetP99Ms / p99Ms;
        }

        // Saturation term: a provider whose bulkhead is full cannot take more
        // work regardless of how well it is performing on the work it has.
        // Floored at 0.25 rather than 0 - saturation is a reason to send less,
        // not a reason to declare the provider broken, and zeroing it here would
        // make a busy provider indistinguishable from a dead one.
        double capacity = 0.25 + 0.75 * clamp(freePermits);

        int score = (int) Math.round(100 * availability * latency * capacity);

        if (breakerState == 2) {
            score = Math.min(score, HALF_OPEN_CAP);
        }

        return new ProviderHealth(pspId, clampScore(score), successRate, p99Ms,
                breakerState, freePermits, samples, dominant(availability, latency, capacity, breakerState));
    }

    /**
     * A half-open breaker is letting a handful of probes through to decide
     * whether the provider is back. Giving it a full share of traffic on the
     * strength of those probes is the thundering herd the phase plan warns
     * about, so its score is capped until the breaker closes and the ordinary
     * signals have real evidence behind them again.
     *
     * <p><strong>12, tuned against a measured oscillation.</strong> The first
     * value was 30, and a 90-second fault produced this, sampled every 2 s as a
     * share of attempts:
     *
     * <pre>
     *   t+8s    psp-a  0.0%   breaker opened
     *   t+17s   psp-a 14.1%   <- readmitted
     *   t+29s   psp-a 16.7%   <- again
     *   t+41s   psp-a  9.4%   <- again
     *   t+53s   psp-a 12.9%   <- again
     * </pre>
     *
     * <p>A clean ~12 s cycle, matching the breaker's own 10 s
     * {@code waitInOpenSeconds}. Every time it half-opened, a score of 30 bought
     * roughly a sixth of the traffic - while the breaker was only willing to
     * admit five probe calls. Everything past the fifth was refused instantly and
     * became a failed payment, so each probe cycle cost real money to re-learn
     * something the previous cycle had already established.
     *
     * <p>12 sits just above {@link #UNROUTABLE}, so a recovering provider still
     * receives a trickle - which it must, because routing is where the breaker's
     * probes come from, and a provider starved of all traffic can never prove it
     * is back. That is the stale-health trap and it is the reason this is a cap
     * rather than a gate.
     */
    private static final int HALF_OPEN_CAP = 12;

    private static String dominant(double availability, double latency, double capacity, int breakerState) {
        if (breakerState == 2) {
            return "breaker half-open - probing";
        }
        double worst = Math.min(availability, Math.min(latency, capacity));
        if (worst > 0.9) {
            return "healthy";
        }
        if (worst == availability) {
            return "failing calls";
        }
        if (worst == latency) {
            return "slow";
        }
        return "saturated";
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }
}
