package com.payorch.connector.health;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.payorch.connector.config.ProviderConfig;
import com.payorch.connector.config.ProviderConfigStore;
import org.infra.observability.ProviderHealth;
import org.infra.observability.ProviderLatency;
import org.infra.observability.ProviderOutcomes;
import org.infra.resilience.breaker.CircuitBreakers;
import org.infra.resilience.bulkhead.Bulkhead;

/**
 * Scores every configured provider, here, because this is the only service that
 * can.
 *
 * <p>All four of phase 5's signals are held by {@code psp-connector} and by
 * nothing else: it owns the circuit breakers, it owns the bulkhead permits, and
 * it is the only process that makes a provider call and can therefore time one
 * or see it fail. The orchestrator could not compute this if it wanted to.
 *
 * <p>So health is <em>published</em> from here rather than assembled at the
 * point of decision, and the routing service consumes it. That split is also
 * what makes the eventual multi-instance story work: each connector knows its
 * own view, and a router aggregating several of them is a change to the
 * consumer, not to this class.
 *
 * <h2>The latency target is per provider, and comes from config</h2>
 *
 * <p>Scoring every provider against one global target would be meaningless here.
 * {@code psp-a} answers in 200 ms and {@code psp-b} takes 2.5 s <em>by
 * contract</em> - psp-b is not unhealthy for being slow, it is slow for a
 * living. The target is derived from each provider's own configured deadline
 * slice, so "slow" means "slower than this provider promised", which is the only
 * definition that lets one score rank providers that are legitimately different.
 */
public class ProviderHealthService {

    private final ProviderConfigStore configs;
    private final ProviderOutcomes outcomes;
    private final ProviderLatency latency;
    private final CircuitBreakers breakers;
    private final Bulkhead bulkhead;

    public ProviderHealthService(ProviderConfigStore configs,
                                 ProviderOutcomes outcomes,
                                 ProviderLatency latency,
                                 CircuitBreakers breakers,
                                 Bulkhead bulkhead) {
        this.configs = configs;
        this.outcomes = outcomes;
        this.latency = latency;
        this.breakers = breakers;
        this.bulkhead = bulkhead;
    }

    /** Every enabled provider, worst first - the order an operator wants to read. */
    public List<ProviderHealth> all() {
        return configs.all().values().stream()
                .filter(ProviderConfig::enabled)
                .map(config -> of(config.pspId()))
                .sorted(Comparator.comparingInt(ProviderHealth::score))
                .toList();
    }

    public ProviderHealth of(String pspId) {
        ProviderConfig config = configs.find(pspId).orElse(null);
        long targetMs = config == null ? 1_000 : targetFor(config);

        return ProviderHealth.score(
                pspId,
                outcomes.successRate(pspId, "authorize"),
                latency.p99Ms(pspId, "authorize"),
                breakerState(pspId),
                freePermits(pspId, config),
                outcomes.samples(pspId, "authorize"),
                targetMs);
    }

    /**
     * What "on time" means for this provider.
     *
     * <p>The configured deadline slice is what this system has already decided it
     * is willing to wait for one attempt, so it is the honest target: a provider
     * whose P99 exceeds its own slice is one whose calls are being abandoned.
     * Reusing it rather than adding a {@code target_p99_ms} column also means
     * there is one number to change instead of two that can disagree - and two
     * numbers that disagree about the same thing is how 3f's config drift starts.
     */
    private long targetFor(ProviderConfig config) {
        return Math.max(1, config.deadlineSliceMs());
    }

    /**
     * 0 closed, 1 open, 2 half-open.
     *
     * <p>Read from the registry map rather than through
     * {@code CircuitBreakers.state()}, which goes via {@code forOperation} and
     * therefore <em>creates</em> a breaker for any provider it is asked about.
     * Health is polled every couple of seconds for every configured provider, so
     * that accessor would quietly instantiate a breaker - and a metric series -
     * for providers nothing has ever called. Reading is not supposed to change
     * what is being read.
     *
     * <p>An absent breaker means no call has been made yet, which is CLOSED for
     * scoring purposes: nothing has gone wrong.
     */
    private int breakerState(String pspId) {
        String state = breakers.states().get(pspId + ":authorize");
        if (state == null) {
            return 0;
        }
        return switch (state) {
            case "OPEN", "FORCED_OPEN" -> 1;
            case "HALF_OPEN" -> 2;
            default -> 0;
        };
    }

    /**
     * Free permits as a fraction of the provider's configured width.
     *
     * <p>Absent from the map means no call has been made to this provider yet, so
     * nothing is held: fully free, which is the truthful answer and keeps a
     * newly-configured provider from being scored as saturated before it has done
     * anything.
     */
    private double freePermits(String pspId, ProviderConfig config) {
        Map<String, Integer> available = bulkhead.available();
        Integer free = available.get(pspId);
        if (free == null || config == null || config.bulkheadMaxConcurrent() <= 0) {
            return 1.0;
        }
        return Math.min(1.0, (double) free / config.bulkheadMaxConcurrent());
    }
}
