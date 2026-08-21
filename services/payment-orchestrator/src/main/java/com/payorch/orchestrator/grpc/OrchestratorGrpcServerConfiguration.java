package com.payorch.orchestrator.grpc;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.payorch.orchestrator.PaymentService;

/**
 * The orchestrator's gRPC server, for the edge to call. Phase 9a.
 *
 * <h2>Same arrangement as the connector's, and that is the point</h2>
 *
 * <p>REST stays. Both transports serve at once, selected by the caller, for the
 * reasons {@code GrpcServerConfiguration} one hop down already states: the
 * benchmark needs the REST arm to be the real one, and a transport swap that
 * cannot be turned off is a deploy with no rollback.
 *
 * <p>Off unless asked for, and that default was learned the hard way — the
 * connector's server defaulted on for exactly one build and the entire
 * psp-connector suite failed to bind port 9090, because the real container was
 * running and already held it.
 *
 * <h2>A different port from the connector's, obviously, and why it is stated</h2>
 *
 * <p>9091 rather than 9090. Both services run on the same Docker network and a
 * shared default would work in containers and collide the moment two servers run
 * on one host — which is exactly what happens when somebody runs this stack
 * outside Docker to debug it.
 */
@Configuration
@ConditionalOnProperty(name = "payorch.grpc.server.enabled", havingValue = "true")
public class OrchestratorGrpcServerConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(OrchestratorGrpcServerConfiguration.class);

    @Bean
    public PaymentsGrpcService paymentsGrpcService(PaymentService payments) {
        return new PaymentsGrpcService(payments);
    }

    @Bean
    public PaymentsGrpcServerLifecycle paymentsGrpcServerLifecycle(
            PaymentsGrpcService service,
            @Value("${payorch.grpc.server.port:9091}") int port) {
        return new PaymentsGrpcServerLifecycle(service, port);
    }

    /**
     * Start on refresh, drain on shutdown.
     *
     * <p>The drain budget is the connector's, for the reason phase 7i measured:
     * a server that is killed rather than drained strands in-flight payments in
     * {@code AUTHORIZING}, and 20 seconds fits well inside the 35s Spring drain
     * and 45s Docker grace period that phase established.
     */
    public static class PaymentsGrpcServerLifecycle implements InitializingBean, DisposableBean {

        private final PaymentsGrpcService service;
        private final int port;
        private Server server;

        public PaymentsGrpcServerLifecycle(PaymentsGrpcService service, int port) {
            this.service = service;
            this.port = port;
        }

        @Override
        public void afterPropertiesSet() throws IOException {
            server = ServerBuilder.forPort(port).addService(service).build().start();
            log.info("gRPC payments service listening on {}", port);
        }

        @Override
        public void destroy() throws InterruptedException {
            if (server == null) {
                return;
            }
            server.shutdown();
            if (!server.awaitTermination(20, TimeUnit.SECONDS)) {
                log.warn("gRPC payments server did not drain in 20s - forcing");
                server.shutdownNow();
            }
        }
    }
}
