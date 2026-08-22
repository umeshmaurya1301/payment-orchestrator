package com.payorch.orchestrator.grpc;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import org.infra.web.ApiException;
import com.payorch.orchestrator.PaymentService;
import com.payorch.orchestrator.api.OrchestratorApi;
import com.payorch.proto.v1.CapturePaymentRequest;
import com.payorch.proto.v1.CreatePaymentRequest;
import com.payorch.proto.v1.GetPaymentRequest;
import com.payorch.proto.v1.PaymentsGrpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * The edge-facing gRPC door, and whether it answers the same as the REST one.
 *
 * <h2>The bug this class was written for</h2>
 *
 * <p>Capturing an already-captured payment answers <strong>409 "Capture
 * refused"</strong> over REST. Over gRPC it answered <strong>502 "The payment
 * could not be confirmed. It may or may not have been created."</strong>
 *
 * <p>{@code PaymentService} raises {@code ApiException(CONFLICT)}, the gRPC
 * service had no branch for it, so it fell to the generic handler and became
 * {@code INTERNAL} — which the edge reads as "no answer". That is the worst
 * available response: it tells a merchant we do not know, about the one case
 * where we know exactly. The payment is captured, nothing further happened, and
 * repeating the request is pointless rather than dangerous.
 *
 * <p>ADR 0009 is entirely about not making this mistake one hop down. The
 * mistake was made again one hop up, in code written after that ADR, and it was
 * found by running a second capture by hand rather than by any test. So these
 * assertions exist, and the ones that matter are named after the divergence
 * rather than after the mechanism.
 */
class PaymentsGrpcServiceTest {

    private Server server;
    private ManagedChannel channel;
    private PaymentService payments;

    @BeforeEach
    void startInProcessServer() throws Exception {
        payments = mock(PaymentService.class);

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new PaymentsGrpcService(payments))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void stop() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private PaymentsGrpc.PaymentsBlockingStub stub() {
        return PaymentsGrpc.newBlockingStub(channel);
    }

    private OrchestratorApi.PaymentResponse aPayment(String state) {
        return new OrchestratorApi.PaymentResponse(
                "01a02518-a536-731b-996b-ba569ef61f67", "merchant-1", state,
                4200, "INR", "424242", "4242", "mockpsp", "mock_ref_1",
                null, "ref-1", Instant.parse("2026-08-21T10:00:00Z"),
                Instant.parse("2026-08-21T10:00:01Z"));
    }

    private CreatePaymentRequest.Builder validCreate() {
        return CreatePaymentRequest.newBuilder()
                .setMerchantId("merchant-1")
                .setAmountMinor(4200)
                .setCurrency("INR")
                .setCardToken("tok_test")
                .setCardBin("424242")
                .setCardLast4("4242");
    }

    private Status captureStatusWhenServiceThrows(RuntimeException t) {
        doThrow(t).when(payments).capture(any());
        try {
            stub().capture(CapturePaymentRequest.newBuilder()
                    .setPaymentId("01a02518-a536-731b-996b-ba569ef61f67").build());
        } catch (StatusRuntimeException e) {
            return e.getStatus();
        }
        throw new AssertionError("the call should have failed");
    }

    // ---------------------------------------------------------------------
    // THE REGRESSION
    // ---------------------------------------------------------------------

    /**
     * The exact divergence, pinned. FAILED_PRECONDITION is what
     * {@code GrpcOrchestratorClient} turns back into
     * {@code CaptureRefusedException(409)}, which is the 409 the REST arm gives.
     */
    @Test
    void anAlreadyCapturedPaymentIsRefusedRatherThanUnknown() {
        Status status = captureStatusWhenServiceThrows(new ApiException(
                HttpStatus.CONFLICT, "not_capturable",
                "payment is not in a capturable state"));

        assertThat(status.getCode())
                .as("a refusal is an ANSWER; INTERNAL would be read as 'we do not know'")
                .isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(status.getCode()).isNotEqualTo(Status.Code.INTERNAL);
        assertThat(status.getDescription()).isEqualTo("not_capturable");
    }

    @Test
    void aMissingPaymentIsNotFound() {
        Status status = captureStatusWhenServiceThrows(new ApiException(
                HttpStatus.NOT_FOUND, "payment_not_found", "no such payment"));

        assertThat(status.getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }

    /**
     * The one case that genuinely IS "we do not know" keeps that meaning. A
     * provider hop that could not answer must not be flattened into a refusal —
     * the inverse of the bug above, and the more expensive direction, because a
     * merchant told "refused" about a possibly-charged payment stops chasing it.
     */
    @Test
    void aProviderThatCouldNotAnswerStaysUnavailable() {
        Status status = captureStatusWhenServiceThrows(new ApiException(
                HttpStatus.BAD_GATEWAY, "connector_unavailable", "no usable response"));

        assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }

    /**
     * An unrecognised status is INTERNAL rather than guessed at. Silently
     * mapping an unknown code onto "the caller can fix this" turns a fault of
     * ours into a merchant-visible rejection.
     */
    @Test
    void anUnmappedApiStatusIsInternal() {
        Status status = captureStatusWhenServiceThrows(new ApiException(
                HttpStatus.I_AM_A_TEAPOT, "weird", "nothing raises this"));

        assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    void anUnexpectedFailureIsInternalAndNotUnknown() {
        Status status = captureStatusWhenServiceThrows(
                new IllegalArgumentException("a bug, not a payment problem"));

        assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(status.getCode())
                .as("UNKNOWN is a word this system has already spent - see ADR 0009")
                .isNotEqualTo(Status.Code.UNKNOWN);
    }

    // ---------------------------------------------------------------------
    // THE VALIDATION THE REST DOOR GETS FROM @Valid AND THIS ONE DOES NOT
    // ---------------------------------------------------------------------

    /**
     * A gRPC service method is not a Spring MVC handler, so nothing applies the
     * Jakarta constraints on {@code OrchestratorApi.CreatePaymentRequest}, and
     * protobuf's type system cannot express "at least 1". Left alone this door
     * would accept a zero-amount payment the other door rejects.
     */
    @Test
    void theTwoDoorsRejectTheSameRequests() {
        for (CreatePaymentRequest bad : new CreatePaymentRequest[] {
                validCreate().setAmountMinor(0).build(),
                validCreate().setCurrency("RUPEES").build(),
                validCreate().setMerchantId("").build(),
                validCreate().setCardToken("").build(),
                validCreate().setCardBin("42").build(),
                validCreate().setCardLast4("42").build() }) {

            try {
                stub().create(bad);
                throw new AssertionError("gRPC accepted a request REST rejects: " + bad);
            } catch (StatusRuntimeException e) {
                assertThat(e.getStatus().getCode())
                        .as("the caller can fix this and no provider was contacted")
                        .isEqualTo(Status.Code.INVALID_ARGUMENT);
            }
        }
    }

    // ---------------------------------------------------------------------
    // THE HAPPY PATH, so the assertions above are about mapping
    // ---------------------------------------------------------------------

    @Test
    void anAuthorizedPaymentComesBackIntact() {
        doReturn(aPayment("AUTHORIZED")).when(payments).create(any());

        var response = stub().create(validCreate().setMerchantReference("ref-1").build());

        assertThat(response.getState()).isEqualTo("AUTHORIZED");
        assertThat(response.getAmountMinor()).isEqualTo(4200);
        assertThat(response.getPspId()).isEqualTo("mockpsp");
        assertThat(response.getCreatedAtMs())
                .isEqualTo(Instant.parse("2026-08-21T10:00:00Z").toEpochMilli());
    }

    /**
     * Protobuf throws on {@code setString(null)} rather than treating it as
     * absent, and half of these fields are legitimately null — {@code errorCode}
     * on success, {@code providerRef} while UNKNOWN. Without the null guards a
     * successful payment would fail inside the response builder and surface as
     * INTERNAL, converting a working payment into a fault.
     */
    @Test
    void nullFieldsDoNotBecomeAnInternalError() {
        doReturn(new OrchestratorApi.PaymentResponse(
                "01a02518-a536-731b-996b-ba569ef61f67", "merchant-1", "UNKNOWN",
                4200, "INR", "424242", "4242", null, null, null, null, null, null))
                .when(payments).create(any());

        var response = stub().create(validCreate().build());

        assertThat(response.getState()).isEqualTo("UNKNOWN");
        // Protobuf has no null: absent arrives as "" and 0, and the CLIENT is
        // what turns those back into null. See GrpcOrchestratorClient.
        assertThat(response.getProviderRef()).isEmpty();
        assertThat(response.getCreatedAtMs()).isZero();
    }

    @Test
    void aMalformedIdIsNotFoundRatherThanInvalidArgument() {
        try {
            stub().get(GetPaymentRequest.newBuilder().setPaymentId("not-a-uuid").build());
            throw new AssertionError("should have failed");
        } catch (StatusRuntimeException e) {
            assertThat(e.getStatus().getCode())
                    .as("there is no payment at that address, and saying which ids "
                            + "are well-formed leaks which ids are worth guessing")
                    .isEqualTo(Status.Code.NOT_FOUND);
        }
    }

    @Test
    void aPaymentThatDoesNotExistIsNotFound() {
        doReturn(Optional.empty()).when(payments).find(any());

        try {
            stub().get(GetPaymentRequest.newBuilder()
                    .setPaymentId("01a02518-a536-731b-996b-ba569ef61f67").build());
            throw new AssertionError("should have failed");
        } catch (StatusRuntimeException e) {
            assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        }
    }
}
