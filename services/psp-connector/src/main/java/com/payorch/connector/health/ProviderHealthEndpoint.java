package com.payorch.connector.health;

import java.util.LinkedHashMap;
import java.util.Map;

import org.infra.observability.ProviderHealth;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * {@code /actuator/providerhealth} - how this service currently rates every
 * provider, and why.
 *
 * <p>Two audiences, and they want the same bytes for different reasons:
 *
 * <ul>
 *   <li>the <strong>orchestrator</strong> polls it and routes on it, which is
 *       what makes phase 5 work at all;</li>
 *   <li>an <strong>operator</strong> reads it during an incident to answer "why
 *       is nothing going to psp-a", which a score alone does not answer - hence
 *       {@code reason}, and hence every input being reported next to the output
 *       it produced.</li>
 * </ul>
 *
 * <p>An endpoint rather than a metric scrape, because a routing decision that
 * depends on a monitoring system is a routing decision that stops working when
 * the monitoring does. Phase 4 built the same number as a metric for humans; this
 * is the machine-readable copy on the payment path, and it deliberately does not
 * go through SigNoz to get there.
 */
@Endpoint(id = "providerhealth")
public class ProviderHealthEndpoint {

    private final ProviderHealthService health;

    public ProviderHealthEndpoint(ProviderHealthService health) {
        this.health = health;
    }

    @ReadOperation
    public Map<String, Object> read() {
        Map<String, Object> providers = new LinkedHashMap<>();
        for (ProviderHealth h : health.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("score", h.score());
            row.put("routable", h.routable());
            row.put("reason", h.reason());
            row.put("successRate", h.successRate());
            row.put("p99Ms", h.p99Ms());
            row.put("breakerState", h.breakerState());
            row.put("freePermits", h.freePermits());
            row.put("samples", h.samples());
            providers.put(h.pspId(), row);
        }
        return Map.of("providers", providers);
    }
}
