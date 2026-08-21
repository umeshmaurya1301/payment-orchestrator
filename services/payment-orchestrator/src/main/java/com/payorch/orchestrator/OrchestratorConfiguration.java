package com.payorch.orchestrator;

import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import com.payorch.orchestrator.connector.ConnectorClient;
import com.payorch.orchestrator.connector.GrpcConnectorClient;
import com.payorch.orchestrator.connector.RestConnectorClient;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
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
            @Value("${payorch.connector.grpc-target:psp-connector:9090}") String target) {
        return ManagedChannelBuilder.forTarget(target)
                // No TLS on the internal hop yet. mTLS is 9b's criterion and is
                // not done; saying so here is better than a reader assuming the
                // channel is encrypted because it is gRPC.
                .usePlaintext()
                .keepAliveTime(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
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
