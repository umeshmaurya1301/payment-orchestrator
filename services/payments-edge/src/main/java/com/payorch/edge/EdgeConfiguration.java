package com.payorch.edge;

import com.payorch.edge.merchant.ApiKeyAuthFilter;
import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import com.payorch.infra.resilience.deadline.Deadlines;
import com.payorch.infra.idempotency.WaitBudget;
import com.payorch.edge.merchant.ApiKeyUsageRecorder;
import com.payorch.edge.merchant.MerchantApiKeyRepository;
import com.payorch.edge.merchant.MerchantRepository;
import com.payorch.edge.orchestrator.GrpcOrchestratorClient;
import com.payorch.edge.orchestrator.OrchestratorClient;
import com.payorch.edge.orchestrator.RestOrchestratorClient;
import com.payorch.edge.orchestrator.OrchestratorClient;
import com.payorch.edge.orchestrator.RestOrchestratorClient;
import com.payorch.edge.orchestrator.GrpcOrchestratorClient;
import com.payorch.infra.resilience.ratelimit.EndpointCosts;
import com.payorch.infra.resilience.ratelimit.RateLimitFilter;
import com.payorch.infra.resilience.ratelimit.RateLimiters;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
// Shaded 1.80.0: GrpcSslContexts and NettyChannelBuilder live under
// io.grpc.netty.shaded.*, verified against the resolved jar in
// GrpcServerConfiguration (psp-connector), where this was worked out first.
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    /**
     * REST unless told otherwise. Phase 9a made this hop selectable the same way
     * the connector hop is, and the default stays REST for the same reason: the
     * benchmark needs the REST arm to be the one actually serving traffic, and a
     * transport swap that cannot be turned off is a deploy with no rollback.
     */
    @Bean
    @ConditionalOnProperty(name = "payorch.orchestrator.transport", havingValue = "rest",
            matchIfMissing = true)
    public OrchestratorClient restOrchestratorClient(
            @Value("${payorch.orchestrator.base-url}") String baseUrl,
            DeadlinePropagation propagation,
            DeadlineExecutor deadlines,
            ObservationRegistry observations) {
        return new RestOrchestratorClient(baseUrl, propagation, deadlines, observations);
    }

    /**
     * The channel is a bean so it is shared, keep-alive'd and closed properly.
     *
     * <p>Phase 9's trap list: use connection pooling and keep-alive on both arms
     * or you are measuring TCP handshakes. A channel per call would multiplex
     * nothing and hand gRPC a handicap {@code RestClient}, which pools by
     * default, does not have.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "payorch.orchestrator.transport", havingValue = "grpc")
    public ManagedChannel orchestratorChannel(
            @Value("${payorch.orchestrator.grpc-target:payment-orchestrator:9091}") String target,
            @Value("${payorch.grpc.tls.enabled:false}") boolean tlsEnabled,
            @Value("${payorch.grpc.tls.cert-file:}") String certFile,
            @Value("${payorch.grpc.tls.key-file:}") String keyFile,
            @Value("${payorch.grpc.tls.ca-file:}") String caFile) {

        if (!tlsEnabled) {
            return ManagedChannelBuilder.forTarget(target)
                    .usePlaintext()
                    .keepAliveTime(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
        }

        // Phase 9b. This service's own cert, presented as the client
        // credential payment-orchestrator's server side now requires - the
        // payments-edge identity issued by docker/certs/generate-certs.sh,
        // trusted only because it is signed by the same CA the orchestrator
        // trusts, and named nothing else.
        return NettyChannelBuilder.forTarget(target)
                .sslContext(clientSslContext(certFile, keyFile, caFile))
                .keepAliveTime(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    /**
     * Identical in shape to {@code OrchestratorConfiguration.clientSslContext}
     * one hop down, and deliberately not shared - see that method's javadoc for
     * why duplicating a dozen lines beats a shared TLS module here.
     */
    private static SslContext clientSslContext(String certFile, String keyFile, String caFile) {
        if (certFile.isBlank() || keyFile.isBlank() || caFile.isBlank()) {
            throw new IllegalStateException(
                    "payorch.grpc.tls.enabled is true but cert-file, key-file or ca-file is "
                            + "blank - mTLS was turned on without the certificates to back it");
        }
        try {
            return GrpcSslContexts.forClient()
                    .keyManager(new java.io.File(certFile), new java.io.File(keyFile))
                    .trustManager(new java.io.File(caFile))
                    .build();
        } catch (javax.net.ssl.SSLException e) {
            throw new IllegalStateException(
                    "could not build a client SSL context from " + certFile + ", " + keyFile
                            + ", " + caFile, e);
        }
    }

    @Bean
    @ConditionalOnProperty(name = "payorch.orchestrator.transport", havingValue = "grpc")
    public OrchestratorClient grpcOrchestratorClient(
            ManagedChannel orchestratorChannel,
            @Value("${payorch.deadline.budget-ms:30000}") long defaultBudgetMs) {
        return new GrpcOrchestratorClient(orchestratorChannel, defaultBudgetMs);
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
    public ApiKeyAuthFilter apiKeyAuthFilter(MerchantRepository merchants,
                                             MerchantApiKeyRepository keys,
                                             ApiKeyUsageRecorder usage) {
        return new ApiKeyAuthFilter(merchants, keys, usage);
    }

    /**
     * 9b. How stale {@code last_used_at} is allowed to be.
     *
     * <p>A minute, not a second, and the reason is in {@link ApiKeyUsageRecorder}:
     * the consumer is a person deciding whether a retiring key is safe to
     * revoke, and they cannot use a value accurate to the second any differently
     * from one accurate to the minute. Setting it to zero turns every
     * authenticated request into a database write — experiment 23 measured this
     * edge at 279 payments/s, so that is 279 writes/s bought for nothing.
     */
    @Bean
    public ApiKeyUsageRecorder apiKeyUsageRecorder(
            MerchantApiKeyRepository keys,
            @Value("${payorch.api-keys.usage-stamp-interval-ms:60000}") long intervalMs) {

        return new ApiKeyUsageRecorder(keys, java.time.Duration.ofMillis(intervalMs));
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
