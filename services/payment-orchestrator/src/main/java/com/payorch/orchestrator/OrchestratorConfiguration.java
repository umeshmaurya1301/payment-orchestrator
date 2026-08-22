package com.payorch.orchestrator;

import org.infra.resilience.deadline.DeadlineExecutor;
import org.infra.resilience.deadline.DeadlinePropagation;
import com.payorch.orchestrator.connector.ConnectorClient;
import com.payorch.orchestrator.connector.GrpcConnectorClient;
import com.payorch.orchestrator.connector.RestConnectorClient;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
// Shaded 1.80.0: GrpcSslContexts and NettyChannelBuilder live under
// io.grpc.netty.shaded.*, not the unshaded io.grpc.netty.* older examples
// show - verified against the resolved jar in GrpcServerConfiguration, one
// hop down, where this was worked out the first time.
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.payorch.orchestrator.routing.HealthWeightedRouter;
import com.payorch.orchestrator.routing.ProviderHealthStore;
import com.payorch.orchestrator.routing.RoutingMetrics;

@Configuration
@EnableScheduling
public class OrchestratorConfiguration {

    /**
     * REST or gRPC, chosen by one property. Phase 9a.
     *
     * <p>Both stay runnable, deliberately, for the reason
     * {@code EVENTS_PUBLISHER} did through the whole of phase 6: a benchmark
     * comparing two transports needs both arms present, and a "before" that can
     * only be quoted rather than re-run stops being evidence the moment anybody
     * doubts it.
     *
     * <p>REST remains the default. gRPC is not yet measured, and a transport
     * swap that becomes the default before its benchmark exists is exactly the
     * kind of change this project's discipline is aimed at - the component
     * arrives, the numbers arrive later, and nobody goes back.
     */
    @Bean
    @ConditionalOnProperty(name = "payorch.connector.transport", havingValue = "rest",
            matchIfMissing = true)
    public ConnectorClient restConnectorClient(
            @Value("${payorch.connector.base-url}") String baseUrl,
            DeadlinePropagation propagation,
            DeadlineExecutor deadlines,
            ObservationRegistry observations) {
        return new RestConnectorClient(baseUrl, propagation, deadlines, observations);
    }

    /**
     * The channel is a bean so it is shared and closed properly.
     *
     * <p>Phase 9's trap list: "benchmarking gRPC against unoptimised REST - use
     * connection pooling and keep-alive on both, or you are measuring TCP
     * handshakes". A channel created per call would multiplex nothing and would
     * hand gRPC a handicap the REST arm does not have, since {@code RestClient}
     * already pools.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "payorch.connector.transport", havingValue = "grpc")
    public ManagedChannel connectorChannel(
            @Value("${payorch.connector.grpc-target:psp-connector:9090}") String target,
            @Value("${payorch.grpc.tls.enabled:false}") boolean tlsEnabled,
            @Value("${payorch.grpc.tls.cert-file:}") String certFile,
            @Value("${payorch.grpc.tls.key-file:}") String keyFile,
            @Value("${payorch.grpc.tls.ca-file:}") String caFile) {

        if (!tlsEnabled) {
            // Plaintext is still reachable - not every environment this runs in
            // has cert-init's output to point at, and a dev box running the
            // jar directly rather than through Docker Compose is exactly that
            // environment. Chosen explicitly rather than defaulted silently:
            // see the TLS-off default in application.yml for why.
            return ManagedChannelBuilder.forTarget(target)
                    .usePlaintext()
                    .keepAliveTime(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
        }

        // Phase 9b. This service's OWN cert, presented here as a CLIENT
        // credential - the same file OrchestratorGrpcServerConfiguration
        // presents as a SERVER credential one hop up. mTLS means every party
        // authenticates, not only the server: psp-connector's grants already
        // assume the caller is this service and nothing else, and until this
        // bean existed that assumption was enforced by nothing on the wire.
        SslContext sslContext = clientSslContext(certFile, keyFile, caFile);
        return NettyChannelBuilder.forTarget(target)
                .sslContext(sslContext)
                .keepAliveTime(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    /**
     * Shared by both gRPC client channels this service builds - this one, to
     * psp-connector, and the edge's equivalent bean one hop up build the same
     * shape independently rather than through a shared module, for the reason
     * {@code GrpcServerConfiguration.TlsFiles} states: these services are
     * independently deployable and a shared TLS module is the coupling this
     * project has already rejected once.
     */
    static SslContext clientSslContext(String certFile, String keyFile, String caFile) {
        if (certFile.isBlank() || keyFile.isBlank() || caFile.isBlank()) {
            throw new IllegalStateException(
                    "payorch.grpc.tls.enabled is true but cert-file, key-file or ca-file is "
                            + "blank - mTLS was turned on without the certificates to back it");
        }
        try {
            return GrpcSslContexts.forClient()
                    // keyManager: THIS client's own identity, presented to the
                    // server so it can verify who is calling. Without it the
                    // handshake is one-directional TLS - the client verifies
                    // the server and the server verifies nothing, which is the
                    // half of mTLS that is easy to forget because a
                    // connection still succeeds without it.
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
    @ConditionalOnProperty(name = "payorch.connector.transport", havingValue = "grpc")
    public ConnectorClient grpcConnectorClient(ManagedChannel connectorChannel) {
        return new GrpcConnectorClient(connectorChannel);
    }

    /**
     * The one metric in this system that is about payments rather than about
     * machinery. See {@link PaymentOutcomeMetrics} for why it took until phase 4
     * to notice it was missing.
     */
    @Bean
    public PaymentOutcomeMetrics paymentOutcomeMetrics(MeterRegistry registry) {
        return new PaymentOutcomeMetrics(registry);
    }

    /**
     * Phase 5. Polls psp-connector's health view rather than asking per payment -
     * see {@link ProviderHealthStore} for why a synchronous hop here would fail
     * exactly when it is needed.
     */
    @Bean
    public ProviderHealthStore providerHealthStore(
            @Value("${payorch.connector.base-url}") String connectorBaseUrl) {
        return new ProviderHealthStore(connectorBaseUrl);
    }

    @Bean
    public HealthWeightedRouter healthWeightedRouter(ProviderHealthStore health) {
        return new HealthWeightedRouter(health);
    }

    @Bean
    public RoutingMetrics routingMetrics(ProviderHealthStore health) {
        return new RoutingMetrics(health);
    }
}
