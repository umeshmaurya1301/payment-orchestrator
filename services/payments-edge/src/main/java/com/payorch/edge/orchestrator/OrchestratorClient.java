package com.payorch.edge.orchestrator;

import java.time.Instant;
import java.util.Optional;

/**
 * The edge's view of {@code payment-orchestrator}, over whichever transport.
 *
 * <h2>Why this became an interface in 9a</h2>
 *
 * <p>It was a class calling REST. It is now the contract two implementations
 * satisfy — {@link RestOrchestratorClient} and {@link GrpcOrchestratorClient} —
 * selected by {@code ORCHESTRATOR_TRANSPORT} at startup, exactly as
 * {@code CONNECTOR_TRANSPORT} selects one hop down.
 *
 * <p>The nested types live here rather than on either implementation, and that
 * is what made the change cheap: {@code PaymentsController} refers to
 * {@code OrchestratorClient.PaymentResponse} and
 * {@code OrchestratorClient.CaptureRefusedException} and needed no edit at all.
 * A transport swap that forces changes in the calling code is a transport swap
 * that will not be reverted under pressure.
 *
 * <h2>The two exception types are the contract, not the convenience</h2>
 *
 * <p>{@link CaptureRefusedException} means the orchestrator answered and the
 * answer was no — a genuine fact about the payment, which the edge turns into a
 * 409 for the merchant. {@link OrchestratorUnavailableException} means there is
 * no answer. Collapsing them would tell a merchant "we do not know what
 * happened" about a case where we know exactly what happened, which is the same
 * distinction ADR 0009 draws one hop further down.
 *
 * <p>The request record remains a deliberate second copy of the orchestrator's
 * own rather than a shared DTO module: two services sharing a jar share a
 * release cycle. The protobuf definitions are now the check that the two copies
 * still agree — which is the role phase 3a's comment predicted for them.
 */
public interface OrchestratorClient {

    PaymentResponse create(CreatePaymentRequest request);

    PaymentResponse capture(String paymentId);

    Optional<PaymentResponse> find(String paymentId);

    record CreatePaymentRequest(
            String merchantId,
            long amountMinor,
            String currency,
            String cardToken,
            String cardBin,
            String cardLast4,
            String merchantReference) {
    }

    record PaymentResponse(
            String id,
            String merchantId,
            String state,
            long amountMinor,
            String currency,
            String cardBin,
            String cardLast4,
            String pspId,
            String providerRef,
            String errorCode,
            String merchantReference,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** The orchestrator answered, and the answer was no. */
    class CaptureRefusedException extends RuntimeException {

        private final int status;

        public CaptureRefusedException(int status, String body) {
            super("capture refused: " + body);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    class OrchestratorUnavailableException extends RuntimeException {

        public OrchestratorUnavailableException(Throwable cause) {
            super("no usable response from payment-orchestrator", cause);
        }
    }
}
