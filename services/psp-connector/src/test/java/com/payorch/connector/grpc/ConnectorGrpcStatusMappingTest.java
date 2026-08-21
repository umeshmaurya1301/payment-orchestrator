package com.payorch.connector.grpc;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import com.payorch.connector.AuthorizationService;
import com.payorch.connector.CaptureService;
import com.payorch.connector.ReversalService;
import com.payorch.connector.api.ConnectorApi;
import com.payorch.connector.provider.PspAdapter;
import com.payorch.connector.provider.StatusFanout;
import com.payorch.infra.resilience.bulkhead.BulkheadFullException;
import com.payorch.infra.resilience.ratelimit.RateLimitedException;
import com.payorch.proto.v1.AuthorizeRequest;
import com.payorch.proto.v1.ConnectorGrpc;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The server half of the gRPC status contract. Phase 9a.
 *
 * <h2>Why this exists, in one sentence</h2>
 *
 * <p>The REST controller answers <strong>503 when nothing was sent</strong> and
 * <strong>502 when the outcome is unknown</strong>, and the orchestrator turns
 * the first into {@code FAILED} and the second into {@code UNKNOWN}. Getting
 * that backwards over gRPC marks a payment {@code FAILED} while the card may
 * have been charged — so the mapping is a correctness contract, not formatting,
 * and until now nothing checked it.
 *
 * <h2>Why in-process rather than calling the method</h2>
 *
 * <p>{@code ConnectorGrpcService.respond} is private and it should stay that
 * way, but the deeper reason is that a test calling the mapping directly asserts
 * the code I wrote rather than the code the runtime runs. Statuses are produced
 * by {@code StreamObserver.onError} and consumed after a trip through the
 * serializer; an exception thrown outside that path — or one thrown after
 * {@code onNext} — does not become the status you expect. In-process transport
 * exercises the real client, server and status path with no port and no
 * container, which is exactly the seam under test.
 *
 * <h2>The pairing with the client half</h2>
 *
 * <p>This asserts <em>exception → status</em>. {@code GrpcStatusTranslationTest}
 * in payment-orchestrator asserts <em>status → exception</em>. Together they
 * cover the round trip; separately, each is a test of one module, which is why
 * they are not one test. If they ever disagree, the wire is the thing that
 * decides and both are written against it.
 */
class ConnectorGrpcStatusMappingTest {

    private Server server;
    private ManagedChannel channel;

    private AuthorizationService authorizations;
    private CaptureService captures;
    private ReversalService reversals;
    private StatusFanout fanout;

    @BeforeEach
    void startInProcessServer() throws Exception {
        authorizations = mock(AuthorizationService.class);
        captures = mock(CaptureService.class);
        reversals = mock(ReversalService.class);
        fanout = mock(StatusFanout.class);

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new ConnectorGrpcService(authorizations, captures, reversals, fanout))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void stop() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private ConnectorGrpc.ConnectorBlockingStub stub() {
        return ConnectorGrpc.newBlockingStub(channel);
    }

    private AuthorizeRequest anyAuthorize() {
        return AuthorizeRequest.newBuilder()
                .setReference("01a01546-f43d-7b4d-bad5-9c290b8e9b62")
                .setPspId("mockpsp")
                .setAmountMinor(4200)
                .setCurrency("INR")
                .setCardToken("tok_test")
                .build();
    }

    /**
     * The status produced when the service throws {@code t}.
     *
     * <p>{@code doThrow().when()} rather than {@code when().thenThrow()}: the
     * latter calls the method to build the stub, so the second call in a test
     * that stubs twice throws during setup rather than during the call.
     */
    private Status statusFor(Throwable t) {
        doThrow(t).when(authorizations).authorize(any());
        try {
            stub().authorize(anyAuthorize());
        } catch (StatusRuntimeException e) {
            return e.getStatus();
        }
        throw new AssertionError("the call should have failed with " + t.getClass().getSimpleName());
    }

    // ---------------------------------------------------------------------
    // NOTHING WAS SENT.  The REST arm answers 503; the card was not charged.
    // ---------------------------------------------------------------------

    @Test
    void anOpenBreakerIsUnavailableAndSaysWhy() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("mockpsp");
        breaker.transitionToOpenState();

        Status status = statusFor(CallNotPermittedException.createCallNotPermittedException(breaker));

        assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(status.getDescription())
                .as("the client distinguishes refused from unknown by this string")
                .isEqualTo("circuit_open");
    }

    @Test
    void aFullBulkheadIsUnavailable() {
        Status status = statusFor(new BulkheadFullException("mockpsp", 50));

        assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(status.getDescription()).isEqualTo("bulkhead_full");
    }

    /**
     * RESOURCE_EXHAUSTED rather than UNAVAILABLE, and the distinction is the one
     * {@code Retry-After} carries in the REST arm: this is our own budget, so
     * retrying sooner cannot help. Generic gRPC retry layers treat the two codes
     * differently, which is the practical reason to spend a separate code here.
     */
    @Test
    void egressRateLimitingIsResourceExhausted() {
        Status status = statusFor(new RateLimitedException("mockpsp", 200));

        assertThat(status.getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThat(status.getDescription()).isEqualTo("provider_rate_limited");
    }

    // ---------------------------------------------------------------------
    // THE OUTCOME IS UNKNOWN.  The REST arm answers 502; the card may have
    // been charged, and this is the case that costs money to get wrong.
    // ---------------------------------------------------------------------

    @Test
    void aProviderThatDidNotAnswerIsTaggedUnknown() {
        Status status = statusFor(
                new PspAdapter.ProviderUnavailableException("mockpsp", new RuntimeException("read timed out")));

        assertThat(status.getCode())
                .as("gRPC has no code for 'I tried and do not know', so it shares UNAVAILABLE")
                .isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(status.getDescription())
                .as("which makes this string the ONLY thing separating a refusal from a maybe-charge")
                .isEqualTo(ConnectorGrpcService.OUTCOME_UNKNOWN);
    }

    /**
     * The two UNAVAILABLE cases must not be confusable, because they mean
     * opposite things about the cardholder's money.
     */
    @Test
    void theTwoUnavailableCasesCarryDifferentDescriptions() {
        Status refused = statusFor(new BulkheadFullException("mockpsp", 50));
        Status maybeCharged = statusFor(
                new PspAdapter.ProviderUnavailableException("mockpsp", new RuntimeException("boom")));

        assertThat(refused.getCode()).isEqualTo(maybeCharged.getCode());
        assertThat(refused.getDescription()).isNotEqualTo(maybeCharged.getDescription());
    }

    // ---------------------------------------------------------------------
    // OUR OWN FAULT.  Deliberately not Status.UNKNOWN.
    // ---------------------------------------------------------------------

    /**
     * An unhandled exception becomes {@code Status.UNKNOWN} by gRPC default, and
     * this system reads the word UNKNOWN as "the card may have been charged".
     * Letting the default through would make every NullPointerException in this
     * service manufacture an UNKNOWN payment and a status poll. INTERNAL says
     * the same thing about severity without borrowing a word that already means
     * something here.
     */
    @Test
    void anUnclassifiedFailureIsInternalAndNotUnknown() {
        Status status = statusFor(new IllegalStateException("a bug, not a provider problem"));

        assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(status.getCode())
                .as("UNKNOWN is a word this system has already spent")
                .isNotEqualTo(Status.Code.UNKNOWN);
        assertThat(status.getDescription()).isEqualTo("internal_error");
    }

    /**
     * The message is dropped on purpose. {@code getDescription()} returning
     * "a bug, not a provider problem" would put an internal exception message on
     * the wire, and status descriptions are the one part of a gRPC error that
     * gets logged everywhere by everyone.
     */
    @Test
    void anInternalErrorDoesNotLeakItsMessage() {
        Status status = statusFor(new IllegalStateException("select * from card_vault failed at host db-3"));

        assertThat(status.getDescription()).doesNotContain("card_vault", "db-3");
    }

    // ---------------------------------------------------------------------
    // THE HAPPY PATH, so the failure assertions above mean something.
    // ---------------------------------------------------------------------

    @Test
    void anApprovedAuthorizationIsNotAnError() {
        when(authorizations.authorize(any())).thenReturn(new ConnectorApi.AuthorizeResponse(
                "mock_aoqw6w2bn43giuje49m0", ConnectorApi.Outcome.APPROVED, null, "AUTH01"));

        var response = stub().authorize(anyAuthorize());

        assertThat(response.getProviderRef()).isEqualTo("mock_aoqw6w2bn43giuje49m0");
        assertThat(response.getAuthCode()).isEqualTo("AUTH01");
    }

    /**
     * A decline is a business outcome, not a transport failure. Turning it into
     * a non-OK status would make the breaker count declines as errors and open
     * on a merchant whose customers happen to have expired cards.
     */
    @Test
    void aDeclineIsAnOkResponseWithADeclinedOutcome() {
        when(authorizations.authorize(any())).thenReturn(new ConnectorApi.AuthorizeResponse(
                "mock_declined", ConnectorApi.Outcome.DECLINED, "insufficient_funds", null));

        var response = stub().authorize(anyAuthorize());

        assertThat(response.getOutcome()).isEqualTo(com.payorch.proto.v1.Outcome.OUTCOME_DECLINED);
        assertThat(response.getErrorCode()).isEqualTo("insufficient_funds");
        // Protobuf has no null: an absent authCode arrives as "".
        assertThat(response.getAuthCode()).isEmpty();
    }

    /**
     * The null-guarding in the service is not defensive noise. Protobuf throws
     * on {@code setString(null)}, so a declined authorization — which
     * legitimately has no authCode — would fail inside the response builder and
     * surface as INTERNAL, converting a clean decline into a fault.
     */
    @Test
    void aNullFieldDoesNotBecomeAnInternalError() {
        when(authorizations.authorize(any())).thenReturn(
                new ConnectorApi.AuthorizeResponse(null, ConnectorApi.Outcome.DECLINED, null, null));

        assertThatCode(() -> stub().authorize(anyAuthorize()))
                .as("a decline with no authCode must not become a transport fault")
                .doesNotThrowAnyException();
    }
}
