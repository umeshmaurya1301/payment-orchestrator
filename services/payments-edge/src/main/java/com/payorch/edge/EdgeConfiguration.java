package com.payorch.edge;

import com.payorch.edge.merchant.ApiKeyAuthFilter;
import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import com.payorch.edge.merchant.MerchantRepository;
import com.payorch.edge.orchestrator.OrchestratorClient;
import com.payorch.infra.resilience.ratelimit.EndpointCosts;
import com.payorch.infra.resilience.ratelimit.RateLimitFilter;
import com.payorch.infra.resilience.ratelimit.RateLimiters;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class EdgeConfiguration {

    @Bean
    public OrchestratorClient orchestratorClient(@Value("${payorch.orchestrator.base-url}") String baseUrl,
                                                 DeadlinePropagation propagation,
                                                 DeadlineExecutor deadlines) {
        return new OrchestratorClient(baseUrl, propagation, deadlines);
    }

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(MerchantRepository merchants) {
        return new ApiKeyAuthFilter(merchants);
    }

    /**
     * 3e's ingress admission control.
     *
     * <p>Ordered <strong>after</strong> {@link ApiKeyAuthFilter}, which costs a
     * merchant lookup on requests that are about to be refused and is still
     * correct: the per-merchant bucket is keyed on an authenticated identity,
     * and a bucket keyed on an unauthenticated header is one the caller chooses.
     * A caller who chooses their own key sends a fresh one per request and never
     * runs out of tokens, which is not a rate limiter with a flaw - it is a
     * counter.
     *
     * <p>The classifier lives here rather than in the starter because what an
     * endpoint costs is a fact about this service, not about the library.
     */
    @Bean
    public RateLimitFilter rateLimitFilter(RateLimiters limiters) {
        return new RateLimitFilter(
                limiters.merchant(),
                limiters.endpoint(),
                EdgeConfiguration::classify,
                Ordered.HIGHEST_PRECEDENCE + 30);
    }

    /**
     * Which bucket a request spends from.
     *
     * <p>Matched on method plus prefix rather than on the exact path, because
     * {@code GET /v1/payments/{id}} carries a different id every time and a
     * bucket keyed by the full URI would give every payment its own allowance.
     * That is the same defect as keying on a caller-controlled header, arrived
     * at by accident.
     */
    private static RateLimitFilter.EndpointCost classify(HttpServletRequest request) {
        boolean write = "POST".equals(request.getMethod());
        return new RateLimitFilter.EndpointCost(
                write ? EndpointCosts.PAYMENTS_WRITE : EndpointCosts.PAYMENTS_READ, 1);
    }
}
