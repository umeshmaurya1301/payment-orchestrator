package com.payorch.orchestrator.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How a merchant's payments choose between providers.
 *
 * <p>Per merchant, because merchants buy different things. One wants the highest
 * authorization rate and will pay for it; another sells low-margin goods and
 * wants the cheapest provider that still works; a third has a commercial
 * agreement that says a named provider gets first refusal. A global switch
 * cannot express any of that.
 *
 * <p>Every strategy shares one non-negotiable: <strong>a provider below
 * {@link com.payorch.infra.observability.ProviderHealth#UNROUTABLE} is never
 * chosen</strong>, whatever the strategy would otherwise prefer. "Cheapest" with
 * no health floor routes to whichever provider is failing most cheaply, and
 * "priority" with no health floor is phase 5a's baseline, which sent 100% of the
 * traffic to a provider declining four calls in five.
 *
 * <p>So the strategies differ in how they rank the <em>routable</em> providers,
 * not in whether they respect health at all.
 */
public enum RoutingStrategy {

    /**
     * The phase-5b default: priority rank decayed by a quarter per step, health
     * squared, weighted random.
     *
     * <p>Spreads load rather than picking a winner, which is what keeps every
     * provider's health signal fresh and lets the system degrade in proportion
     * rather than in steps. The right default for a merchant with no strong
     * opinion, and measured at 97.5% steady-state success against static
     * priority's 99.7% - the 2 points being what the exploration traffic costs.
     */
    HEALTH_WEIGHTED,

    /**
     * Lowest rolling P99 among providers above the health floor.
     *
     * <p>For merchants where checkout abandonment is the dominant cost: a
     * shopper who waits four seconds for an authorization is a shopper who may
     * not wait at all. Deterministic rather than weighted, so it will happily
     * send everything to one provider - which is the point, and which is also
     * why it needs the health floor more than the others do.
     *
     * <p>A provider with no recent calls reports a P99 of -1. Those are ranked
     * <em>last</em> rather than first, which is the opposite of what a naive
     * "smallest number wins" would do and is the difference between exploring
     * an unknown provider and handing it all the traffic.
     */
    LEAST_LATENCY,

    /**
     * Lowest {@code cost_bps} among providers above the health floor.
     *
     * <p>The health floor is doing the real work here. Without it this strategy
     * finds the provider that fails most cheaply, because a provider that
     * declines everything costs nothing per successful transaction - it has
     * none.
     */
    CHEAPEST,

    /**
     * Strictly the first routable provider by priority.
     *
     * <p>Phase 1's behaviour, kept as a named strategy rather than left as the
     * fallback, because a merchant on a commercial agreement needs it to be a
     * decision somebody made rather than a state the system fell into.
     *
     * <p>Note it is not identical to phase 1: unroutable providers are still
     * skipped. A named provider getting first refusal is a business rule; a
     * named provider getting first refusal while its breaker is open is a bug.
     */
    PRIORITY;

    private static final Logger log = LoggerFactory.getLogger(RoutingStrategy.class);

    /**
     * Parses a database value, falling back to {@link #HEALTH_WEIGHTED}.
     *
     * <p>Falls back rather than throwing, deliberately. This is read on the
     * payment path from a column an operator can edit at runtime, and the
     * failure mode of throwing is that one typo stops every payment for that
     * merchant. Routing by a sensible default while complaining loudly is the
     * better trade - and it is why the column is a varchar rather than an enum,
     * which would have made adding a strategy a schema migration.
     */
    public static RoutingStrategy parse(String value) {
        if (value == null || value.isBlank()) {
            return HEALTH_WEIGHTED;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("unknown routing strategy '{}' - falling back to {}", value, HEALTH_WEIGHTED);
            return HEALTH_WEIGHTED;
        }
    }
}
