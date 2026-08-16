package com.payorch.connector.config;

import java.util.Map;
import java.util.TreeMap;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * {@code /actuator/pspconfig} - what this service believes right now.
 *
 * <p>Exists because "the change took effect without a restart" is a claim, and a
 * claim about running state needs somewhere to be checked. Reading the database
 * proves what was written; reading the log proves a change was seen. Neither
 * proves the value currently in force inside the process, which is the only
 * thing that actually governs a payment.
 *
 * <p>Deliberately reports the store's own view rather than re-querying. A
 * re-query would answer the wrong question - it would show what the database
 * says, which is exactly what an unapplied change also shows.
 */
@Endpoint(id = "pspconfig")
public class ProviderConfigEndpoint {

    private final ProviderConfigStore store;

    public ProviderConfigEndpoint(ProviderConfigStore store) {
        this.store = store;
    }

    @ReadOperation
    public Map<String, Object> read() {
        Map<String, Object> providers = new TreeMap<>();
        store.all().forEach((pspId, config) -> providers.put(pspId, Map.of(
                "displayName", config.displayName(),
                "baseUrl", config.baseUrl(),
                "enabled", config.enabled(),
                "priority", config.priority(),
                "updatedAt", config.updatedAt().toString(),
                "deadlineSliceMs", config.deadlineSliceMs(),
                "retryMaxAttempts", config.retryMaxAttempts(),
                "breaker", Map.of(
                        "failureRateThreshold", config.breakerFailureRateThreshold(),
                        "windowSeconds", config.breakerWindowSeconds(),
                        "minimumCalls", config.breakerMinimumCalls(),
                        "waitInOpenSeconds", config.breakerWaitInOpenSeconds(),
                        "halfOpenPermits", config.breakerHalfOpenPermits()),
                "bulkhead", Map.of(
                        "maxConcurrent", config.bulkheadMaxConcurrent(),
                        "maxWaitMs", config.bulkheadMaxWaitMs()),
                "egressTps", config.egressTps())));

        return Map.of(
                "providers", providers,
                "reloads", store.reloads(),
                "changesApplied", store.changesApplied(),
                "reloadFailures", store.failures(),
                "secondsSinceLastChange", store.secondsSinceLastChange());
    }
}
