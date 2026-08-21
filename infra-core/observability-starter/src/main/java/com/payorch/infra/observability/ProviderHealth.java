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
     * <h2>The breaker is a gate for BOTH open and half-open states</h2>
     *
     * <p>An open breaker means calls are not reaching the provider at all, so
     * there is nothing to weigh: the score is 0 and the provider is skipped.
     *
     * <p><strong>Half-open used to be capped rather than gated</strong> - eligible
     * for a trickle of real traffic, on the reasoning that routing was the only
     * way the breaker's own half-open probes ever got made, and a provider
     * starved of all traffic can never prove it is back. Experiment 09 measured
     * what that trickle cost: ~4 of the phase's residual 6.3 points of "no spike
     * in error rate" were real customer payments spent proving a provider had
     * recovered.
     *
     * <p>It is gated to 0 now, matching a fully open breaker, because
     * {@code psp-connector}'s {@code SyntheticProber} supplies the half-open
     * evidence instead - a synthetic {@code authorize} call through the exact
     * same breaker, on money nobody spent. The stale-health trap this cap
     * originally answered is still real; the fix moved from "accept some real
     * losses" to "stop needing a real customer for the test at all". See
     * {@code SyntheticProber}'s javadoc for the mechanism and experiment 09's
     * section D for the measurement this closes.
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
        if (breakerState == 2) {
            return new ProviderHealth(pspId, 0, successRate, p99Ms, breakerState,
                    freePermits, samples, "breaker half-open - probed synthetically, not with real traffic");
        }

        // No evidence: sit at neutral rather than inventing a verdict. See NEUTRAL.
        if (samples == 0 || successRate < 0) {
            return new ProviderHealth(pspId, NEUTRAL, successRate, p99Ms, breakerState,
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

        // breakerState is always 0 (closed) by this point - 1 and 2 both
        // returned above - so it is not threaded into dominant(), which used to
        // take it solely to report "breaker half-open - probing" for a case
        // that now has its own reason string at the point of the early return.
        return new ProviderHealth(pspId, clampScore(score), successRate, p99Ms,
                breakerState, freePermits, samples, dominant(availability, latency, capacity));
    }

    /**
     * 12, formerly. Until this experiment, a half-open breaker's score was
     * capped rather than gated to 0, at this value - tuned down from 30 against
     * a measured ~12-second oscillation matching the breaker's own
     * {@code waitInOpenSeconds}, because a score of 30 was buying roughly a
     * sixth of the traffic against a breaker only willing to admit five probe
     * calls in half-open, and everything past the fifth became a failed
     * payment.
     *
     * <p>The cap existed because routing was the only way the breaker's
     * half-open probes ever got made, and a provider starved of all traffic can
     * never prove it is back - the stale-health trap. {@code SyntheticProber}
     * answers that trap a different way now: probes come from a scheduled
     * synthetic call rather than from routing real payments to a provider still
     * being tested, so the score can gate to 0 and the trickle this constant
     * used to buy is unnecessary. See {@link #score} for where the gate lives
     * now and experiment 09 for what the cap cost while it was the only answer.
     */

    private static String dominant(double availability, double latency, double capacity) {
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
