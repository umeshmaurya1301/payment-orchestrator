package com.payorch.connector.grpc;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import io.grpc.Server;
import io.grpc.ServerBuilder;
// grpc-netty-shaded 1.80.0 shades ALL of io.grpc.netty.* and io.netty.* under
// its own io.grpc.netty.shaded.* prefix - including GrpcSslContexts and
// NettyServerBuilder, which older documentation and the unshaded grpc-netty
// artifact both expose unshaded. Verified against the resolved jar rather
// than assumed: the unshaded import compiles against nothing in this
// artifact and fails at the same spot every time.
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
 *
 * <h2>mTLS, phase 9b</h2>
 *
 * <p>{@code payorch.grpc.tls.enabled} switches the server between plain
 * {@code ServerBuilder.forPort} and a Netty-specific builder carrying an
 * {@code SslContext} with {@code ClientAuth.REQUIRE} — a caller with no
 * certificate, or one not signed by this deployment's CA, cannot complete the
 * handshake, which is checked directly in {@code docker/certs/generate-certs.sh}
 * and {@code tools/security/mtls-demo.sh} rather than only in code review.
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
            @Value("${payorch.grpc.server.port:9090}") int port,
            @Value("${payorch.grpc.tls.enabled:false}") boolean tlsEnabled,
            @Value("${payorch.grpc.tls.cert-file:}") String certFile,
            @Value("${payorch.grpc.tls.key-file:}") String keyFile,
            @Value("${payorch.grpc.tls.ca-file:}") String caFile) {
        return new GrpcServerLifecycle(service, port,
                new TlsFiles(tlsEnabled, certFile, keyFile, caFile));
    }

    /**
     * The three files an mTLS server needs, or none of them. Phase 9b.
     *
     * <p>A record rather than three loose parameters threaded through the
     * lifecycle bean, for the same reason {@code ConnectorApi.AuthorizeRequest}
     * is a record and not four method arguments: {@code enabled=false} with a
     * populated cert path, or {@code enabled=true} with an empty one, are both
     * states a caller can construct by accident, and grouping the fields does
     * not stop that - but it does mean the invalid state is checked in exactly
     * one place, {@link #sslContext()}, rather than wherever a positional
     * argument happened to be passed.
     */
    record TlsFiles(boolean enabled, String certFile, String keyFile, String caFile) {

        SslContext sslContext() {
            if (certFile.isBlank() || keyFile.isBlank() || caFile.isBlank()) {
                throw new IllegalStateException(
                        "payorch.grpc.tls.enabled is true but cert-file, key-file or ca-file "
                                + "is blank - mTLS was turned on without the certificates to back it");
            }
            try {
                // REQUIRE, not OPTIONAL. A server that accepts an unauthenticated
                // caller when it CAN authenticate one is not doing mTLS, it is
                // doing TLS with an unused feature - the entire point of this
                // criterion is that a caller with no certificate, or the wrong
                // one, cannot complete the handshake at all.
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
        private final TlsFiles tls;
        private Server server;

        public GrpcServerLifecycle(ConnectorGrpcService service, int port, TlsFiles tls) {
            this.service = service;
            this.port = port;
            this.tls = tls;
        }

        @Override
        public void afterPropertiesSet() throws IOException {
            if (tls.enabled()) {
                // NettyServerBuilder rather than the transport-agnostic
                // ServerBuilder.forPort: TLS is a Netty-specific concern and
                // ServerBuilder has no sslContext method to express it, because
                // not every gRPC transport implementation has a concept of one.
                server = NettyServerBuilder.forPort(port)
                        .addService(service)
                        .sslContext(tls.sslContext())
                        .build()
                        .start();
                log.info("gRPC connector listening on {} (mTLS, client cert required)", port);
            } else {
                server = ServerBuilder.forPort(port)
                        .addService(service)
                        .build()
                        .start();
                log.info("gRPC connector listening on {} (plaintext)", port);
            }
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
