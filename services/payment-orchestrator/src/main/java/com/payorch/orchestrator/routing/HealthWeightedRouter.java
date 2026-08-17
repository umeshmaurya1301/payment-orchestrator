package com.payorch.orchestrator.routing;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import com.payorch.infra.observability.ProviderHealth;
import com.payorch.orchestrator.domain.PspConfig;

/**
 * Chooses a provider by health, weighted, with priority as the tie-break.
 *
 * <p>This is the class phase 5 exists for: the breaker's state stops being a
 * terminal outcome and becomes an input. A breaker that opens fails calls fast;
 * a score that drops moves traffic before anyone notices.
 *
 * <h2>Weighted random, and why that IS the damping</h2>
 *
 * <p>The phase plan lists routing oscillation as a trap - A degrades, everything
 * moves to B, B saturates, everything moves back - and requires picking a damping
 * strategy deliberately. The choice here is proportional weighting rather than
 * hysteresis or a dwell timer, because it removes the cliff instead of delaying
 * it:
 *
 * <ul>
 *   <li><strong>Best-score-wins</strong> is a step function. One point of score
 *       difference moves 100% of the traffic, so the two providers trade the
 *       entire load back and forth on noise. Hysteresis makes that flip slower,
 *       not smaller.</li>
 *   <li><strong>Weighted</strong> is continuous. A provider that is 10% worse
 *       receives proportionally less, so a small score change moves a small
 *       amount of traffic and the system settles instead of ringing.</li>
 * </ul>
 *
 * <p>Weights are the <strong>square</strong> of the score, which is the knob
 * between "spread evenly" and "always pick the best". Squared, a healthy
 * provider at 100 against a neutral one at 50 takes 80% rather than 67% - enough
 * preference that the good provider carries the traffic, not so much that the
 * others go dark.
 *
 * <p>That residue is load-bearing rather than a rounding error. It is what keeps
 * every provider's health signal <em>fresh</em>, which is the phase-5 trap about
 * scores going stale: a provider receiving no traffic generates no signal, so it
 * can never prove it has recovered. A trickle of real payments is a better probe
 * than a synthetic health check, because it measures the thing that actually
 * matters - and it costs nothing extra, because those payments had to go
 * somewhere anyway.
 *
 * <h2>Priority still counts, and dropping it cost 5.8 points of success</h2>
 *
 * <p>The first version of this class weighted on health alone, and it measured
 * <strong>93.9%</strong> success in the healthy steady state where static
 * priority routing had measured <strong>99.7%</strong>.
 *
 * <p>Nothing was broken. The score is computed against each provider's <em>own</em>
 * contract - psp-b promises 2.5 s and 4% declines, and delivering exactly that
 * makes it healthy - so all four providers scored near 100 and the traffic split
 * roughly evenly. Health had answered "is this provider doing what it promised",
 * which is the right question for <em>rerouting</em> and the wrong one for
 * <em>preferring</em>. The priority column is where "which provider do we
 * actually want" lives: cost, commercial terms, and the fact that psp-a is
 * simply better.
 *
 * <p>So the weight carries both. Priority contributes a rank decay - each step
 * down the list is worth a quarter of the one above - and health modulates it.
 * A healthy preferred provider keeps the overwhelming majority of the traffic;
 * a degraded one collapses out of contention regardless of its rank, which is
 * the entire point of the phase.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <p>It does not fail over. Choosing a provider for a <em>new</em> payment and
 * re-attempting a payment that already went out are different decisions with
 * completely different safety properties, and conflating them is how a system
 * double-charges a customer. Failover is phase 5's next step and has its own
 * rule: only on errors that prove the request was never processed.
 */
public class HealthWeightedRouter {

    private final ProviderHealthStore health;

    public HealthWeightedRouter(ProviderHealthStore health) {
        this.health = health;
    }

    /**
     * @param candidates enabled providers that support the payment's currency,
     *                   already in priority order
     */
    public Optional<PspConfig> choose(List<PspConfig> candidates) {
        return choose(candidates, RoutingStrategy.HEALTH_WEIGHTED);
    }

    /**
     * @param strategy the merchant's chosen strategy - see {@link RoutingStrategy}
     */
    public Optional<PspConfig> choose(List<PspConfig> candidates, RoutingStrategy strategy) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Integer> scores = health.scores();
        if (scores.isEmpty()) {
            // No usable health view: phase 1's rule, which is a defensible thing
            // to be doing rather than a failure. See ProviderHealthStore.
            return Optional.of(candidates.get(0));
        }

        // Providers the health view has no opinion about are treated as neutral
        // rather than dropped. A provider added to psp_config seconds ago is
        // unknown to the connector's window, and refusing to route to it would
        // make a new provider permanently invisible.
        // candidates arrive in priority order, so the index IS the rank.
        List<Weighted> weighted = java.util.stream.IntStream.range(0, candidates.size())
                .mapToObj(rank -> new Weighted(candidates.get(rank),
                        scores.getOrDefault(candidates.get(rank).getPspId(), ProviderHealth.NEUTRAL),
                        rank))
                .filter(w -> w.score() >= ProviderHealth.UNROUTABLE)
                .toList();

        if (weighted.isEmpty()) {
            // Every provider is below the routable floor. Send it to the least
            // bad one rather than declining locally: a provider scoring 4 may
            // still authorize the payment, and a decline here is certain to fail.
            return candidates.stream()
                    .max(java.util.Comparator.comparingInt(
                            c -> scores.getOrDefault(c.getPspId(), ProviderHealth.NEUTRAL)));
        }

        // Every strategy ranks only the ROUTABLE providers. The health floor is
        // not a strategy's business to override: "cheapest" with no floor finds
        // the provider that fails most cheaply, and "priority" with no floor is
        // phase 5a's baseline, which sent everything to a provider declining
        // four calls in five.
        if (strategy != RoutingStrategy.HEALTH_WEIGHTED) {
            return Optional.of(pick(strategy, weighted));
        }

        long total = weighted.stream().mapToLong(Weighted::weight).sum();
        if (total <= 0) {
            return Optional.of(weighted.get(0).config());
        }

        long pick = ThreadLocalRandom.current().nextLong(total);
        for (Weighted w : weighted) {
            pick -= w.weight();
            if (pick < 0) {
                return Optional.of(w.config());
            }
        }
        // Unreachable while total is the sum of the weights, but returning the
        // first candidate is better than an Optional.empty() that would mark a
        // payment unroutable because of an arithmetic edge.
        return Optional.of(weighted.get(0).config());
    }

    /**
     * The deterministic strategies. Each returns a single provider rather than a
     * weighted distribution, which is the trade they make: a merchant asking for
     * "the fastest" wants the fastest, not mostly the fastest.
     *
     * <p>The cost is that a deterministic strategy stops exploring, so the
     * providers it does not choose go quiet and their health scores decay toward
     * neutral. That is survivable because neutral is not zero - an unused
     * provider stays eligible - but it does mean these strategies react to a
     * degradation later than {@code HEALTH_WEIGHTED} does, having less evidence
     * to react to. Worth knowing before recommending one to a merchant.
     */
    private PspConfig pick(RoutingStrategy strategy, List<Weighted> routable) {
        return switch (strategy) {
            // Already in priority order, and already filtered to routable. The
            // difference from phase 1 is exactly that filter.
            case PRIORITY -> routable.get(0).config();

            case CHEAPEST -> routable.stream()
                    .min(Comparator.comparingInt(w -> w.config().getCostBps()))
                    .orElse(routable.get(0))
                    .config();

            // A provider with no recent calls reports p99 = -1. Ranking that as
            // "fastest" would hand every payment to whichever provider is least
            // used, so unknowns sort LAST here and are reached only when nothing
            // has a measurement.
            case LEAST_LATENCY -> routable.stream()
                    .min(Comparator.comparingLong(w -> {
                        long p99 = latencyOf(w);
                        return p99 < 0 ? Long.MAX_VALUE : p99;
                    }))
                    .orElse(routable.get(0))
                    .config();

            case HEALTH_WEIGHTED -> routable.get(0).config();
        };
    }

    /**
     * The rolling P99 the connector published for this provider, or -1.
     *
     * <p>Read from the same health view the scores come from, so a strategy and
     * a score can never disagree about which moment they are describing.
     */
    private long latencyOf(Weighted weighted) {
        return health.p99Ms(weighted.config().getPspId());
    }

    /**
     * How much each step down the priority list costs.
     *
     * <p>A quarter per rank, capped at four ranks so a long provider list does
     * not underflow to zero and silently make the tail unreachable. Chosen
     * against the measured alternative: with no rank decay at all, four
     * contractually-healthy providers split the traffic evenly and steady-state
     * success fell from 99.7% to 93.9%. At a quarter per rank the preferred
     * provider keeps roughly three quarters of the traffic while the others
     * retain enough to keep their health signal alive.
     *
     * <p>It is a preference, not a lock. Health is squared and rank decay is
     * linear in the exponent, so a preferred provider whose score falls from 100
     * to 10 loses a factor of 100 and drops below a neutral provider two ranks
     * beneath it. Degradation beats seniority, which is the behaviour phase 5 is
     * for.
     */
    private static final double RANK_DECAY = 0.25;
    private static final int MAX_RANK = 4;

    private record Weighted(PspConfig config, int score, int rank) {
        long weight() {
            double decay = Math.pow(RANK_DECAY, Math.min(rank, MAX_RANK));
            // Scaled by 1000 before rounding so the decayed weights of low-ranked
            // providers do not all collapse to the same integer.
            return Math.max(1, Math.round(1000.0 * score * score * decay));
        }
    }
}
