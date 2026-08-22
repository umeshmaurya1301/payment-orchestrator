package com.payorch.connector.config;

import java.time.Duration;

import org.infra.resilience.breaker.CircuitBreakers;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/**
 * One row of {@code psp_config}, as this service sees it.
 *
 * <p>Everything phase 3 made configurable, per provider, read from the database
 * rather than from a YAML file. That is the whole of 3f, and the argument for it
 * came out of 3d and 3e rather than out of preference:
 *
 * <ul>
 *   <li>3d measured a bulkhead limit sized from healthy latency turning a merely
 *       slow provider into a 93% decline rate. No constant is right both when a
 *       provider is healthy and when it is not, so the value has to move - and
 *       during an incident, without a restart that would reset the pools and
 *       breakers that are already struggling.</li>
 *   <li>3e's egress limit is the provider's <em>contracted</em> TPS. One number
 *       shared across providers is wrong for all but one of them.</li>
 * </ul>
 *
 * <p>A record, and immutable, because it is republished wholesale on every
 * change. Mutating a shared config object while requests read it is how a
 * bulkhead ends up briefly sized from one provider and waited on by another.
 *
 * @param updatedAt when the row last changed, maintained by MySQL's
 *                  {@code ON UPDATE CURRENT_TIMESTAMP}. Reported, never acted
 *                  on: change detection compares the fields this service
 *                  actually uses, so a cosmetic edit cannot cost a provider its
 *                  circuit breaker history.
 */
public record ProviderConfig(
        String pspId,
        String displayName,
        String baseUrl,
        boolean enabled,
        int priority,
        long deadlineSliceMs,
        int retryMaxAttempts,
        int breakerFailureRateThreshold,
        int breakerWindowSeconds,
        int breakerMinimumCalls,
        int breakerWaitInOpenSeconds,
        int breakerHalfOpenPermits,
        int bulkheadMaxConcurrent,
        long bulkheadMaxWaitMs,
        int egressTps,
        java.time.Instant updatedAt) {

    public CircuitBreakerConfig breakerConfig() {
        return CircuitBreakers.config(
                breakerFailureRateThreshold,
                Duration.ofSeconds(breakerWindowSeconds),
                breakerMinimumCalls,
                Duration.ofSeconds(breakerWaitInOpenSeconds),
                breakerHalfOpenPermits);
    }

    /**
     * The burst the egress limiter allows: one second's worth.
     *
     * <p>Not larger. A burst is a promise that the provider will tolerate a
     * momentary spike above the contracted rate, and nothing in a TPS contract
     * says that. One second is the smallest allowance that keeps normal jitter
     * from being clipped.
     */
    public int egressBurst() {
        return Math.max(1, egressTps);
    }

    /**
     * Whether anything this service acts on differs.
     *
     * <p>Compared field by field rather than by a timestamp or a version, and
     * that is the load-bearing decision of the whole reload path. Rebuilding a
     * circuit breaker discards its sliding window, so treating every write to
     * the row as a change would let an edit to {@code display_name} throw away a
     * provider's failure history - and a poller that did it on a timer would
     * leave every breaker permanently unable to accumulate enough calls to open.
     */
    public boolean sameBehaviourAs(ProviderConfig other) {
        return other != null
                && baseUrl.equals(other.baseUrl)
                && enabled == other.enabled
                && deadlineSliceMs == other.deadlineSliceMs
                && retryMaxAttempts == other.retryMaxAttempts
                && breakerFailureRateThreshold == other.breakerFailureRateThreshold
                && breakerWindowSeconds == other.breakerWindowSeconds
                && breakerMinimumCalls == other.breakerMinimumCalls
                && breakerWaitInOpenSeconds == other.breakerWaitInOpenSeconds
                && breakerHalfOpenPermits == other.breakerHalfOpenPermits
                && bulkheadMaxConcurrent == other.bulkheadMaxConcurrent
                && bulkheadMaxWaitMs == other.bulkheadMaxWaitMs
                && egressTps == other.egressTps;
    }

    /** One line, for the log written whenever this provider's config changes. */
    public String summary() {
        return "bulkhead=%d/%dms breaker=%d%%/%ds/min%d/open%ds/probe%d retries=%d slice=%dms egress=%d/s"
                .formatted(bulkheadMaxConcurrent, bulkheadMaxWaitMs,
                        breakerFailureRateThreshold, breakerWindowSeconds, breakerMinimumCalls,
                        breakerWaitInOpenSeconds, breakerHalfOpenPermits,
                        retryMaxAttempts, deadlineSliceMs, egressTps);
    }
}
