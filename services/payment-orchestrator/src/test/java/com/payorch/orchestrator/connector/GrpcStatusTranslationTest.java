package com.payorch.orchestrator.connector;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import com.payorch.proto.v1.AuthorizeRequest;
import com.payorch.proto.v1.AuthorizeResponse;
import com.payorch.proto.v1.ConnectorGrpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client half of the gRPC status contract. Phase 9a.
 *
 * <h2>What the two exception types mean</h2>
 *
 * <p>{@code PaymentService} has exactly two questions to ask about a failed
 * connector call, and they are not about severity:
 *
 * <pre>
 *   ConnectorRejectedException      nothing was sent   -&gt; FAILED
 *   ConnectorUnavailableException   outcome unknown    -&gt; UNKNOWN
 * </pre>
 *
 * <p>There is no third branch, so <strong>every</strong> gRPC status has to land
 * on one side or the other, including the ones nobody anticipated. That makes
 * the default case a decision rather than an oversight: an unrecognised status
 * must be treated as {@code UNKNOWN}, because a payment wrongly marked unknown
 * costs one status poll and a payment wrongly marked failed while the card was
 * charged costs a refund, a chargeback and a customer.
 *
 * <h2>Why a fake server rather than a mocked stub</h2>
 *
 * <p>A mocked {@code ConnectorBlockingStub} would let the test hand
 * {@code GrpcConnectorClient} any exception it liked, including ones gRPC never
 * produces. Serving a real status over a real channel means the test can only
 * assert about statuses that can actually arrive — and it exercises
 * {@code withDeadlineAfter}, which is a stub decorator and disappears entirely
 * when the stub is a mock.
 *
 * <p>Pairs with {@code ConnectorGrpcStatusMappingTest} in psp-connector, which
 * asserts the other direction.
 */
class GrpcStatusTranslationTest {

    private Server server;
    private ManagedChannel channel;
    private GrpcConnectorClient client;

    /** Whatever this holds is what the server fails with. Null means succeed. */
    private volatile Status failWith;

    @BeforeEach
    void startInProcessServer() throws Exception {
        failWith = null;

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new ConnectorGrpc.ConnectorImplBase() {
                    @Override
                    public void authorize(AuthorizeRequest request,
                                          StreamObserver<AuthorizeResponse> out) {
                        if (failWith != null) {
                            out.onError(failWith.asRuntimeException());
                            return;
                        }
                        out.onNext(AuthorizeResponse.newBuilder()
                                .setProviderRef("mock_ok")
                                .setOutcome(com.payorch.proto.v1.Outcome.OUTCOME_APPROVED)
                                .setAuthCode("AUTH01")
                                .build());
                        out.onCompleted();
                    }
                })
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = new GrpcConnectorClient(channel);
    }

    @AfterEach
    void stop() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private ConnectorApi.AuthorizeRequest request() {
        return new ConnectorApi.AuthorizeRequest(
                "01a01546-f43d-7b4d-bad5-9c290b8e9b62", "mockpsp",
                4200, "INR", "tok_test", "424242", "4242");
    }

    private void serverFailsWith(Status status) {
        this.failWith = status;
    }

    // ---------------------------------------------------------------------
    // DEFINITELY NOT SENT.  These become FAILED, and must not become UNKNOWN
    // or every open breaker manufactures work for the status poller.
    // ---------------------------------------------------------------------

    @Test
    void anOpenBreakerIsRejectedNotUnknown() {
        serverFailsWith(Status.UNAVAILABLE.withDescription("circuit_open"));

        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(ConnectorClient.ConnectorRejectedException.class);
    }

    @Test
    void aFullBulkheadIsRejected() {
        serverFailsWith(Status.UNAVAILABLE.withDescription("bulkhead_full"));

        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(ConnectorClient.ConnectorRejectedException.class);
    }

    @Test
    void egressRateLimitingIsRejected() {
        serverFailsWith(Status.RESOURCE_EXHAUSTED.withDescription("provider_rate_limited"));

        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(ConnectorClient.ConnectorRejectedException.class);
    }

    // ---------------------------------------------------------------------
    // MAYBE CHARGED.  These become UNKNOWN.
    // ---------------------------------------------------------------------

    /**
     * The same code as an open breaker, the opposite meaning. This one string is
     * the entire difference between "retry freely" and "do not touch it until a
     * lookup says what happened", which is why the server sets it deliberately
     * and why both halves of the contract are tested.
     */
    @Test
    void aProviderThatMayHaveActedIsUnavailable() {
        serverFailsWith(Status.UNAVAILABLE.withDescription("provider_unavailable"));

        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(ConnectorClient.ConnectorUnavailableException.class);
    }

    /**
     * DEADLINE_EXCEEDED is the one people get wrong, because from the caller's
     * seat it feels like a local timeout. It is not: we stopped waiting, the
     * connector did not necessarily stop working, and the provider may already
     * have been called. gRPC cancels the server, but cancellation is not
     * retroactive — a card charged before the cancel arrived stays charged.
     */
    @Test
    void aDeadlineExceededIsUnknownAndNotRejected() {
        serverFailsWith(Status.DEADLINE_EXCEEDED.withDescription("took too long"));

        assertThatThrownBy(() -> client.authorize(request()))
                .as("the request may well have reached a provider before we gave up")
                .isInstanceOf(ConnectorClient.ConnectorUnavailableException.class)
                .isNotInstanceOf(ConnectorClient.ConnectorRejectedException.class);
    }

    @Test
    void anInternalServerFaultIsUnknown() {
        serverFailsWith(Status.INTERNAL.withDescription("internal_error"));

        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(ConnectorClient.ConnectorUnavailableException.class);
    }

    // ---------------------------------------------------------------------
    // THE DEFAULT, which is the part that has to be decided rather than left.
    // ---------------------------------------------------------------------

    /**
     * Statuses this system has never produced and has no opinion about. If a new
     * interceptor, a proxy or a future gRPC version starts emitting one of
     * these, it must fall to the safe side without anybody noticing — which is
     * exactly the situation where nobody is watching.
     */
    @Test
    void anUnrecognisedStatusFallsToUnknown() {
        for (Status status : new Status[] {
                Status.PERMISSION_DENIED, Status.UNAUTHENTICATED, Status.DATA_LOSS,
                Status.FAILED_PRECONDITION, Status.UNIMPLEMENTED, Status.UNKNOWN }) {

            serverFailsWith(status.withDescription("something new"));

            assertThatThrownBy(() -> client.authorize(request()))
                    .as("%s must not be read as 'definitely not charged'", status.getCode())
                    .isInstanceOf(ConnectorClient.ConnectorUnavailableException.class);
        }
    }

    /**
     * UNAVAILABLE with no description at all. A proxy, a load balancer or a
     * connection refused produces this — nobody set the tag, so the description
     * is null rather than {@code "provider_unavailable"}.
     *
     * <p>It maps to REJECTED, and that is a deliberate reading rather than an
     * accident of the {@code equals} check: a connection that was refused never
     * carried a request to a provider. It is also the one branch here where the
     * safe-side rule is knowingly not applied, so it is worth stating plainly —
     * if a proxy ever starts swallowing a real provider timeout into a bare
     * UNAVAILABLE, this is where the payment gets marked FAILED.
     */
    @Test
    void aBareUnavailableIsReadAsRefused() {
        serverFailsWith(Status.UNAVAILABLE);

        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(ConnectorClient.ConnectorRejectedException.class);
    }

    /**
     * The exception type is what the state machine reads; the message is what a
     * human reads at 3am. Both gates below produce the same type, and saying
     * "its circuit is open" for a rate limit — which this exception did until
     * phase 9a — sends that person to inspect a breaker that is closed.
     */
    @Test
    void theRejectionMessageNamesTheGateThatRefused() {
        serverFailsWith(Status.RESOURCE_EXHAUSTED.withDescription("provider_rate_limited"));
        assertThatThrownBy(() -> client.authorize(request()))
                .hasMessageContaining("provider_rate_limited")
                .hasMessageNotContaining("circuit");

        serverFailsWith(Status.UNAVAILABLE.withDescription("bulkhead_full"));
        assertThatThrownBy(() -> client.authorize(request()))
                .hasMessageContaining("bulkhead_full")
                .hasMessageNotContaining("circuit");

        serverFailsWith(Status.UNAVAILABLE.withDescription("circuit_open"));
        assertThatThrownBy(() -> client.authorize(request()))
                .as("and when it IS the breaker, it still says so")
                .hasMessageContaining("circuit_open");
    }

    // ---------------------------------------------------------------------
    // SUCCESS, so that the assertions above are about mapping rather than
    // about the client failing at everything.
    // ---------------------------------------------------------------------

    @Test
    void anApprovedAuthorizationComesBackIntact() {
        ConnectorApi.AuthorizeResponse response = client.authorize(request());

        assertThat(response.providerRef()).isEqualTo("mock_ok");
        assertThat(response.outcome()).isEqualTo(ConnectorApi.Outcome.APPROVED);
        assertThat(response.authCode()).isEqualTo("AUTH01");
    }

    /**
     * Protobuf has no null: an unset string deserializes to {@code ""}. Passing
     * that straight up would turn "this response had no error code" into "the
     * error code is the empty string", which reads as a value in every log line,
     * metric label and database column downstream.
     */
    @Test
    void absentProtobufStringsBecomeNullRatherThanEmpty() {
        ConnectorApi.AuthorizeResponse response = client.authorize(request());

        assertThat(response.errorCode())
                .as("an approved authorization has no error code, and \"\" is not the same as none")
                .isNull();
    }
}
