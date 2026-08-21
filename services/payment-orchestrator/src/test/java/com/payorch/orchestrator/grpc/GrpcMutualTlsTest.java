package com.payorch.orchestrator.grpc;

import java.io.File;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.payorch.proto.v1.GetPaymentRequest;
import com.payorch.proto.v1.PaymentResponse;
import com.payorch.proto.v1.PaymentsGrpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whether {@code ClientAuth.REQUIRE} actually requires a client, over a real
 * socket. Phase 9b.
 *
 * <h2>Why this exists instead of trusting the config</h2>
 *
 * <p>The obvious way to "verify" mTLS is to read {@code GrpcServerConfiguration}
 * and confirm it calls {@code .clientAuth(ClientAuth.REQUIRE)}. That is the
 * failure mode this project keeps finding this session — a component that looks
 * correct in the code and has never been run against the case it exists for. A
 * raw {@code openssl s_client} probe against the live stack made the gap
 * concrete: a connection presenting <strong>no client certificate at all</strong>
 * completed the TLS handshake and printed {@code CONNECTION ESTABLISHED}. RFC
 * 8446 §4.4.2 explains why — a server MAY accept an empty client certificate
 * message at the raw TLS layer and defer the decision to the application. What
 * matters is not whether the handshake completes; it is whether a gRPC call can
 * actually go through, which is what every assertion below checks.
 *
 * <h2>Why {@code keytool} rather than Netty's {@code SelfSignedCertificate}</h2>
 *
 * <p>Netty's own test-certificate utility was the first thing tried here and it
 * fails outright on this project's JDK: it needs either BouncyCastle (not a
 * dependency of this project) or reflection into {@code sun.security.x509},
 * which JDK 25 blocks —
 * {@code UnsupportedOperationException: OpenJdkSelfSignedCertGenerator not
 * supported on the used JDK version}. There is no public JDK API to construct
 * and self-sign an {@code X509Certificate}. {@code keytool} is the one
 * certificate tool guaranteed present everywhere this project's tests run,
 * because it ships inside the same JDK the build already requires — a real
 * process, shelled out to in a {@code @TempDir}, producing genuine PKCS12
 * keystores this test then loads through plain {@code java.security} APIs. No
 * new dependency, and no assumption beyond "a JDK can build this project".
 */
class GrpcMutualTlsTest {

    private Server server;
    private final List<AutoCloseable> channels = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : channels) {
            c.close();
        }
        if (server != null) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * A key and its self-signed certificate, each also written out as a PEM
     * file. {@code GrpcSslContexts.forServer} has no
     * {@code (PrivateKey, X509Certificate)} overload in this grpc-netty-shaded
     * version — only file- and stream-based ones — so the server side needs
     * files regardless. Writing both sides as files rather than mixing forms
     * also makes this test exercise the identical file-loading code path
     * production uses, not merely an in-memory approximation of it.
     */
    private record Identity(PrivateKey key, X509Certificate cert, File certPem, File keyPem) {
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }

    /**
     * One self-signed identity, via {@code keytool -genkeypair}. That single
     * command both generates the keypair and signs the certificate — unlike
     * {@code openssl}'s CSR-then-sign flow, {@code keytool} has no separate
     * signing step for a self-signed leaf, which is exactly the shape this test
     * needs: each identity is its own trust anchor, the same simplification
     * {@code generate-certs.sh}'s CA makes safe to skip for a unit test that
     * only exercises the {@code ClientAuth.REQUIRE} mechanism, not the CA chain.
     */
    private static Identity generateIdentity(File dir, String commonName) throws Exception {
        File keystoreFile = new File(dir, commonName + ".p12");
        String password = "changeit";

        Process keytool = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", commonName,
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1",
                "-storetype", "PKCS12",
                "-keystore", keystoreFile.getAbsolutePath(),
                "-storepass", password,
                "-dname", "CN=" + commonName,
                // Every channel in this test dials "localhost" - all servers
                // and clients here run in the same JVM on loopback - so every
                // identity's SAN says so too, or the JDK's hostname verifier
                // (SAN-based; CN is not a fallback on a modern JDK) fails the
                // handshake before ClientAuth is ever reached, which is a
                // different failure from the one each test means to produce.
                "-ext", "SAN=dns:localhost",
                "-noprompt")
                .redirectErrorStream(true)
                .start();
        String output = new String(keytool.getInputStream().readAllBytes());
        boolean finished = keytool.waitFor(30, TimeUnit.SECONDS);
        if (!finished || keytool.exitValue() != 0) {
            throw new IllegalStateException("keytool failed for " + commonName + ": " + output);
        }

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystoreFile.toPath())) {
            keyStore.load(in, password.toCharArray());
        }
        PrivateKey key = (PrivateKey) keyStore.getKey(commonName, password.toCharArray());
        X509Certificate cert = (X509Certificate) keyStore.getCertificate(commonName);

        // keytool always emits PKCS#8 for -genkeypair, which is the format
        // "PRIVATE KEY" (unqualified) PEM headers denote and the format
        // Netty's PEM key loader expects.
        File certPem = new File(dir, commonName + ".crt.pem");
        File keyPem = new File(dir, commonName + ".key.pem");
        Files.writeString(certPem.toPath(), pem("CERTIFICATE", cert.getEncoded()));
        Files.writeString(keyPem.toPath(), pem("PRIVATE KEY", key.getEncoded()));

        return new Identity(key, cert, certPem, keyPem);
    }

    /** A trivial implementation - only the TLS layer is under test here. */
    private static final PaymentsGrpc.PaymentsImplBase ECHO = new PaymentsGrpc.PaymentsImplBase() {
        @Override
        public void get(GetPaymentRequest request, StreamObserver<PaymentResponse> out) {
            out.onNext(PaymentResponse.newBuilder().setId("reached").build());
            out.onCompleted();
        }
    };

    /**
     * Starts a server presenting {@code serverIdentity}, trusting only callers
     * who present {@code trustedClient}'s certificate - the exact
     * {@code GrpcSslContexts.forServer(...).trustManager(...).clientAuth(REQUIRE)}
     * call {@code GrpcServerConfiguration} and
     * {@code OrchestratorGrpcServerConfiguration} both make in production.
     */
    private int startServer(Identity serverIdentity, Identity trustedClient) throws Exception {
        // OrchestratorGrpcServerConfiguration.TlsFiles, not a parallel
        // reimplementation of the same GrpcSslContexts/ClientAuth call - both
        // classes are in com.payorch.orchestrator.grpc, so this test exercises
        // the ACTUAL production method that builds the server's SslContext.
        // A regression there - ClientAuth.REQUIRE weakened to OPTIONAL, or
        // dropped - fails here, not only in a hand-written parallel of it.
        SslContext serverTls = new OrchestratorGrpcServerConfiguration.TlsFiles(
                true,
                serverIdentity.certPem().getAbsolutePath(),
                serverIdentity.keyPem().getAbsolutePath(),
                trustedClient.certPem().getAbsolutePath())
                .sslContext();

        server = NettyServerBuilder.forPort(0)
                .addService(ECHO)
                .sslContext(serverTls)
                .build()
                .start();
        return server.getPort();
    }

    /**
     * A channel presenting {@code clientIdentity} as its own identity (or none,
     * if {@code clientIdentity} is null) and trusting {@code serverTrust} to
     * verify the server.
     */
    private PaymentsGrpc.PaymentsBlockingStub client(
            int port, Identity clientIdentity, Identity serverTrust) throws Exception {

        var builder = GrpcSslContexts.forClient().trustManager(serverTrust.certPem());
        if (clientIdentity != null) {
            builder.keyManager(clientIdentity.certPem(), clientIdentity.keyPem());
        }
        SslContext clientTls = builder.build();

        var channel = NettyChannelBuilder.forAddress("localhost", port)
                .overrideAuthority("localhost")
                .sslContext(clientTls)
                .build();
        channels.add(channel::shutdownNow);
        return PaymentsGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------------

    @Test
    void aCallerWithTheTrustedCertificateIsAccepted(@TempDir File dir) throws Exception {
        Identity serverIdentity = generateIdentity(dir, "payment-orchestrator");
        Identity trustedClient = generateIdentity(dir, "payments-edge");
        int port = startServer(serverIdentity, trustedClient);

        var response = client(port, trustedClient, serverIdentity)
                .get(GetPaymentRequest.newBuilder().setPaymentId("x").build());

        assertThat(response.getId()).isEqualTo("reached");
    }

    /**
     * The regression this class exists to prevent. A raw TLS probe with no
     * client certificate completed the handshake against the real deployment;
     * this asserts the actual gRPC call — not the handshake — is refused.
     */
    @Test
    void aCallerWithNoCertificateIsRefused(@TempDir File dir) throws Exception {
        Identity serverIdentity = generateIdentity(dir, "payment-orchestrator");
        Identity trustedClient = generateIdentity(dir, "payments-edge");
        int port = startServer(serverIdentity, trustedClient);

        assertThatThrownBy(() ->
                client(port, null, serverIdentity)
                        .get(GetPaymentRequest.newBuilder().setPaymentId("x").build()))
                .as("ClientAuth.REQUIRE must refuse a caller presenting no certificate at all")
                .isInstanceOf(StatusRuntimeException.class);
    }

    /**
     * A certificate that exists and is well-formed, just not one the server was
     * told to trust — the shape of a caller from outside this deployment, or a
     * compromised container presenting its own generated identity.
     */
    @Test
    void aCallerWithAnUntrustedCertificateIsRefused(@TempDir File dir) throws Exception {
        Identity serverIdentity = generateIdentity(dir, "payment-orchestrator");
        Identity trustedClient = generateIdentity(dir, "payments-edge");
        Identity rogue = generateIdentity(dir, "not-payments-edge");
        int port = startServer(serverIdentity, trustedClient);

        assertThatThrownBy(() ->
                client(port, rogue, serverIdentity)
                        .get(GetPaymentRequest.newBuilder().setPaymentId("x").build()))
                .as("a certificate the server was not told to trust must be refused, "
                        + "not merely 'a certificate that happens to exist'")
                .isInstanceOf(StatusRuntimeException.class);
    }

    /** The server's own identity must also be verified — not only the client's. */
    @Test
    void theClientRefusesAServerItDoesNotTrust(@TempDir File dir) throws Exception {
        Identity serverIdentity = generateIdentity(dir, "payment-orchestrator");
        Identity trustedClient = generateIdentity(dir, "payments-edge");
        Identity wrongTrustAnchor = generateIdentity(dir, "not-the-real-orchestrator");
        int port = startServer(serverIdentity, trustedClient);

        assertThatThrownBy(() ->
                client(port, trustedClient, wrongTrustAnchor)
                        .get(GetPaymentRequest.newBuilder().setPaymentId("x").build()))
                .as("a client that does not recognise the server's certificate must refuse it, "
                        + "or mTLS here would only be authenticating callers, not services")
                .isInstanceOf(Exception.class);
    }
}
