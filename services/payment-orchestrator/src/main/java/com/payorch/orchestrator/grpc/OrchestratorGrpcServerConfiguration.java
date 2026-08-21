package com.payorch.orchestrator.grpc;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import io.grpc.Server;
import io.grpc.ServerBuilder;
// grpc-netty-shaded 1.80.0 shades ALL of io.grpc.netty.* and io.netty.* under
// its own io.grpc.netty.shaded.* prefix, verified against the resolved jar
// rather than assumed - see the matching comment in GrpcServerConfiguration
// one hop down, where this was worked out.
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
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
 *
 * <h2>mTLS, phase 9b</h2>
 *
 * <p>This service is BOTH ends of an mTLS pair: a server here, and a client one
 * hop down in {@link com.payorch.orchestrator.OrchestratorConfiguration} for the
 * call to psp-connector. One identity, one cert, used both ways — a caller on
 * the second hop presents the same certificate it verified callers against on
 * the first, because it is one service in both roles.
 *
 * <p>{@code payorch.grpc.tls.enabled} switches this server between plain
 * {@code ServerBuilder.forPort} and a Netty builder carrying an
 * {@code SslContext} with {@code ClientAuth.REQUIRE}: a caller with no
 * certificate, or one not signed by this deployment's CA, cannot complete the
 * handshake.
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
            @Value("${payorch.grpc.server.port:9091}") int port,
            @Value("${payorch.grpc.tls.enabled:false}") boolean tlsEnabled,
            @Value("${payorch.grpc.tls.cert-file:}") String certFile,
            @Value("${payorch.grpc.tls.key-file:}") String keyFile,
            @Value("${payorch.grpc.tls.ca-file:}") String caFile) {
        return new PaymentsGrpcServerLifecycle(service, port,
                new TlsFiles(tlsEnabled, certFile, keyFile, caFile));
    }

    /**
     * The three files an mTLS server needs, or none of them. Phase 9b.
     *
     * <p>Identical in shape to {@code GrpcServerConfiguration.TlsFiles} one hop
     * down, and deliberately not shared between the two: these two services are
     * independently deployable, and a shared TLS-config module would be the
     * same coupling this project has already rejected once, for
     * {@code OrchestratorApi}'s request records duplicating
     * {@code ConnectorApi}'s rather than sharing a jar.
     */
    record TlsFiles(boolean enabled, String certFile, String keyFile, String caFile) {

        SslContext sslContext() {
            if (certFile.isBlank() || keyFile.isBlank() || caFile.isBlank()) {
                throw new IllegalStateException(
                        "payorch.grpc.tls.enabled is true but cert-file, key-file or ca-file "
                                + "is blank - mTLS was turned on without the certificates to back it");
            }
            try {
                return GrpcSslContexts.forServer(new File(certFile), new File(keyFile))
                        .trustManager(new File(caFile))
                        .clientAuth(ClientAuth.REQUIRE)
                        .build();
            } catch (javax.net.ssl.SSLException e) {
                throw new IllegalStateException(
                        "could not build an SSL context from " + certFile + ", " + keyFile
                                + ", " + caFile, e);
            }
        }
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
        private final TlsFiles tls;
        private Server server;

        public PaymentsGrpcServerLifecycle(PaymentsGrpcService service, int port, TlsFiles tls) {
            this.service = service;
            this.port = port;
            this.tls = tls;
        }

        @Override
        public void afterPropertiesSet() throws IOException {
            if (tls.enabled()) {
                server = NettyServerBuilder.forPort(port)
                        .addService(service)
                        .sslContext(tls.sslContext())
                        .build()
                        .start();
                log.info("gRPC payments service listening on {} (mTLS, client cert required)", port);
            } else {
                server = ServerBuilder.forPort(port).addService(service).build().start();
                log.info("gRPC payments service listening on {} (plaintext)", port);
            }
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
