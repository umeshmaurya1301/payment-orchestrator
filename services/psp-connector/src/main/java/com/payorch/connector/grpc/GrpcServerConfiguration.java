package com.payorch.connector.grpc;

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

import com.payorch.connector.AuthorizationService;
import com.payorch.connector.CaptureService;
import com.payorch.connector.ReversalService;
import com.payorch.connector.provider.StatusFanout;

/**
 * The gRPC server, on its own port, alongside the REST one.
 *
 * <h2>Both transports at once, deliberately</h2>
 *
 * <p>The REST controller is not removed and is not going to be until the
 * benchmark has run. Phase 9's trap list warns against "benchmarking gRPC
 * against unoptimised REST", and the only way to be sure the REST arm is the
 * real one is for it to be the same code still serving real traffic.
 *
 * <p>It also keeps the migration reversible. A transport swap that cannot be
 * turned off is a deploy with no rollback, and this project has an established
 * pattern for exactly this - {@code EVENTS_PUBLISHER} kept the dual-write arm
 * runnable through all of phase 6 so its "before" could be re-measured rather
 * than quoted.
 *
 * <h2>Plain grpc-netty rather than a Spring gRPC starter</h2>
 *
 * <p>Twelve lines of lifecycle against a dependency whose version compatibility
 * with Boot 4.1 would be one more thing to establish. This project has met the
 * Boot 4 module reshuffle four times already - Flyway in phase 0, the OTLP
 * property rename in 4, the Mongo URI in 6e, and
 * {@code DataRedisAutoConfiguration} in 8a - and none of those announced
 * themselves. A server this small is not worth a fifth.
 */
@Configuration
// OFF unless asked for, like every other optional listener here
// (EVENTS_PUBLISHER, WEBHOOKS_ENABLED, UNKNOWN_POLLER_ENABLED) and for the
// reason those state: a service that opens a socket nobody configured makes
// every other test and experiment depend on it.
//
// This defaulted ON for exactly one build, and the whole psp-connector test
// suite failed with "Failed to bind to address 0.0.0.0/0.0.0.0:9090" - because
// the real container was running and already held the port. A unit test that
// cannot run while the application is running is a bad test, and the cause was
// a default, not the test.
@ConditionalOnProperty(name = "payorch.grpc.server.enabled", havingValue = "true")
public class GrpcServerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerConfiguration.class);

    @Bean
    public ConnectorGrpcService connectorGrpcService(AuthorizationService authorizations,
                                                     CaptureService captures,
                                                     ReversalService reversals,
                                                     StatusFanout fanout) {
        return new ConnectorGrpcService(authorizations, captures, reversals, fanout);
    }

    @Bean
    public GrpcServerLifecycle grpcServerLifecycle(
            ConnectorGrpcService service,
            @Value("${payorch.grpc.server.port:9090}") int port) {
        return new GrpcServerLifecycle(service, port);
    }

    /**
     * Start on refresh, drain on shutdown.
     *
     * <p>The shutdown half matters more than it looks. Phase 7i measured what
     * happens when a drain budget is not stated in terms of the thing that kills
     * it: six services declared a graceful HTTP shutdown that Docker's 10s
     * default grace period could never have completed, and 36 payments were
     * stranded in AUTHORIZING. A gRPC server that is killed rather than drained
     * makes exactly the same payments unresolvable, so it gets a bounded
     * shutdown that fits inside the same chain.
     */
    public static class GrpcServerLifecycle implements InitializingBean, DisposableBean {

        private final ConnectorGrpcService service;
        private final int port;
        private Server server;

        public GrpcServerLifecycle(ConnectorGrpcService service, int port) {
            this.service = service;
            this.port = port;
        }

        @Override
        public void afterPropertiesSet() throws IOException {
            server = ServerBuilder.forPort(port)
                    .addService(service)
                    .build()
                    .start();
            log.info("gRPC connector listening on {}", port);
        }

        @Override
        public void destroy() throws InterruptedException {
            if (server == null) {
                return;
            }
            server.shutdown();
            // Well inside the 35s Spring drain and the 45s Docker grace period
            // that phase 7i established. Long enough for an in-flight provider
            // call to finish, short enough that it cannot be the reason the
            // container is killed.
            if (!server.awaitTermination(20, TimeUnit.SECONDS)) {
                log.warn("gRPC server did not drain in 20s - forcing");
                server.shutdownNow();
            }
        }

        public int port() {
            return port;
        }
    }
}
