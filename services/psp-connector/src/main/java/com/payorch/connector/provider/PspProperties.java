package com.payorch.connector.provider;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-provider configuration, keyed by {@code psp_id}.
 *
 * <p>A map rather than a flat set of properties even though there is exactly one
 * provider today. Phase 5 adds a second and a third, and the shape of this
 * config is what decides whether that is a one-line change or a refactor of
 * every adapter.
 *
 * <pre>{@code
 * payorch:
 *   psp:
 *     providers:
 *       mockpsp:
 *         base-url: http://mock-psp-simulator:8085
 * }</pre>
 */
@ConfigurationProperties(prefix = "payorch.psp")
public record PspProperties(Map<String, Provider> providers) {

    public PspProperties {
        if (providers == null) {
            providers = Map.of();
        }
    }

    /**
     * @param baseUrl where the provider lives. Note the absence of any timeout
     *        setting: phase 1 has no timeouts anywhere, and phase 3 adds them
     *        here once phase 2 has measured what their absence costs.
     */
    public record Provider(String baseUrl) {
    }

    public Provider require(String pspId) {
        Provider provider = providers.get(pspId);
        if (provider == null || provider.baseUrl() == null || provider.baseUrl().isBlank()) {
            throw new IllegalStateException(
                    "payorch.psp.providers." + pspId + ".base-url is not configured");
        }
        return provider;
    }
}
