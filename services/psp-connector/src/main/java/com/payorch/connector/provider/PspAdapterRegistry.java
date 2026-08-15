package com.payorch.connector.provider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.payorch.infra.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Resolves a {@code pspId} to its adapter.
 *
 * <p>Built from the injected list of adapters rather than a hand-maintained
 * switch, so adding a provider in phase 5 means adding a bean and nothing else.
 */
@Component
public class PspAdapterRegistry {

    private final Map<String, PspAdapter> byPspId;

    public PspAdapterRegistry(List<PspAdapter> adapters) {
        this.byPspId = adapters.stream()
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
        PspAdapter adapter = byPspId.get(pspId);
        if (adapter == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "unknown_psp",
                    "no adapter is configured for psp '" + pspId + "'");
        }
        return adapter;
    }
}
