package com.payorch.edge;

import com.payorch.edge.merchant.ApiKeyAuthFilter;
import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import com.payorch.infra.resilience.deadline.Deadlines;
import com.payorch.infra.idempotency.WaitBudget;
import com.payorch.edge.merchant.MerchantRepository;
import com.payorch.edge.orchestrator.OrchestratorClient;
import com.payorch.infra.resilience.ratelimit.EndpointCosts;
import com.payorch.infra.resilience.ratelimit.RateLimitFilter;
import com.payorch.infra.resilience.ratelimit.RateLimiters;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * <p>{@code @EnableScheduling} arrived in phase 7c, for
 * {@code IdempotencySweeper}. Worth noting rather than assuming: this service
 * had no scheduled work at all until then, so nothing in it would have run - the
 * annotation on the sweeper would have been inert, in the same quiet way
 * {@code @RetryableTopic} was inert in the ledger before
 * {@code @EnableKafkaRetryTopic}, and the only symptom would have been a table
 * that never stopped growing.
 */
@Configuration
@EnableScheduling
public class EdgeConfiguration {

    @Bean
    public OrchestratorClient orchestratorClient(@Value("${payorch.orchestrator.base-url}") String baseUrl,
                                                 DeadlinePropagation propagation,
                                                 DeadlineExecutor deadlines,
                                                 ObservationRegistry observations) {
        return new OrchestratorClient(baseUrl, propagation, deadlines, observations);
    }

    /**
     * Phase 7b. How long a duplicate may wait for the request that beat it.
     *
     * <p>This is the bean that joins two starters the libraries themselves keep
     * apart, and it is the whole reason {@code WaitBudget} is an interface. The
     * answer is not a constant: it is whatever is left of the deadline phase 3a
     * stamped on this request. A duplicate that waited a fixed 250ms while its
     * caller had 40ms left would be writing a reply to a connection nobody is
     * reading - the one unbounded thing in a system built around not having any.
     *
     * <p><strong>The reserve is the part worth explaining.</strong> Handing over
     * the entire remaining budget would mean a waiter that succeeds at the last
     * possible millisecond has nothing left to serialize and write the response
     * with, so it would time out having done all the work. Keeping some back
     * turns "waited too long and failed" into "did not wait that long, and
     * answered".
     *
     * <p>Falls back to a fixed budget outside a request scope, for the reason
     * {@code Deadlines.currentOrDefault} exists: unbounded is the failure mode
     * being removed, so code reached without a deadline should still be bounded
     * by something.
     */
    @Bean
    public WaitBudget idempotencyWaitBudget(
            @Value("${payorch.idempotency.fallback-wait-ms:250}") long fallbackMs,
            @Value("${payorch.idempotency.wait-reserve-ms:200}") long reserveMs) {

        return () -> Deadlines.current()
                .map(deadline -> deadline.remainingMs() - reserveMs)
                .orElse(fallbackMs);
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
