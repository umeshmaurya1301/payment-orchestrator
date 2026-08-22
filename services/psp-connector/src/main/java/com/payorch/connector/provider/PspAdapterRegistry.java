package com.payorch.connector.provider;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.payorch.connector.config.ProviderConfig;
import com.payorch.connector.config.ProviderConfigStore;
import org.infra.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Resolves a {@code pspId} to its adapter.
 *
 * <p>Backed by {@link ProviderConfigStore} rather than by a fixed list of beans -
 * the change 3f required. Providers are rows now, so the set of them is not
 * known when the context is built, and a provider added by an {@code INSERT}
 * has to become routable without a deploy. A list of adapter beans could not
 * express that: adding a provider would mean adding a bean, which means a
 * restart, which is the thing 3f exists to remove.
 *
 * <p>Adapters are cached per {@code pspId} and built on first use. They hold no
 * configuration of their own - each reads the store on every call - so one
 * instance stays correct across any number of config changes, including a change
 * of {@code base_url}.
 */
public class PspAdapterRegistry {

    private final ProviderConfigStore configs;
    private final Function<String, PspAdapter> adapterFactory;
    private final Map<String, PspAdapter> declared;
    private final Map<String, PspAdapter> cache = new ConcurrentHashMap<>();

    /**
     * @param declared adapters contributed as beans. These take precedence and
     *                 do <em>not</em> require a {@code psp_config} row: an
     *                 adapter someone went to the trouble of declaring brings
     *                 its own configuration, which is what a provider speaking a
     *                 bespoke protocol will look like when phase 5 adds one.
     *                 Today the only such adapters are test doubles.
     */
    public PspAdapterRegistry(ProviderConfigStore configs,
                              java.util.List<PspAdapter> declared,
                              Function<String, PspAdapter> adapterFactory) {
        this.configs = configs;
        this.adapterFactory = adapterFactory;
        this.declared = declared.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        PspAdapter::pspId, Function.identity()));
    }

    /**
     * @throws ApiException 400, not 500. An unroutable {@code pspId} is the
     *         caller sending something this service does not support, and
     *         answering 500 would make the orchestrator treat a permanent
     *         configuration error as a transient one worth retrying.
     */
    public PspAdapter require(String pspId) {
        PspAdapter declaredAdapter = declared.get(pspId);
        if (declaredAdapter != null) {
            return declaredAdapter;
        }

        ProviderConfig config = configs.find(pspId).orElseThrow(() -> new ApiException(
                HttpStatus.BAD_REQUEST, "unknown_psp",
                "no adapter is configured for psp '" + pspId + "'"));

        if (!config.enabled()) {
            // A disabled provider is a deliberate operational decision, so it is
            // reported as its own error code rather than folded into "unknown".
            // Turning a provider off during an incident and seeing "unknown_psp"
            // in the logs would send the next person looking for a typo.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "psp_disabled",
                    "psp '" + pspId + "' is disabled in psp_config");
        }

        return cache.computeIfAbsent(pspId, adapterFactory);
    }

    /** Which providers this service could route to right now. */
    public Set<String> routable() {
        Set<String> all = new java.util.TreeSet<>(declared.keySet());
        configs.enabled().stream().map(ProviderConfig::pspId).forEach(all::add);
        return all;
    }
}
