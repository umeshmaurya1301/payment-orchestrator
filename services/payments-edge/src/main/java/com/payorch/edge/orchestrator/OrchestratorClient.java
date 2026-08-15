package com.payorch.edge.orchestrator;

import java.time.Instant;
import java.util.Optional;

import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls {@code payment-orchestrator} over REST.
 *
 * <p><strong>Phase 3a: bounded by the deadline budget.</strong> This is the hop
 * that stamps it - 30 s, and the edge deliberately ignores any budget a merchant
 * sends, because trusting that header would let one header hold a connection for
 * as long as the caller liked.
 *
 * <p>The request record is a deliberate second copy of the orchestrator's own,
 * rather than a shared DTO module. Two services sharing a jar share a release
 * cycle; the duplication is a dozen lines and it is what keeps them
 * independently deployable. Phase 9's protobuf definitions become the check that
 * the two copies still agree.
 */
public class OrchestratorClient {

    private final RestClient client;
    private final DeadlineExecutor deadlines;

    public OrchestratorClient(String baseUrl, DeadlinePropagation propagation, DeadlineExecutor deadlines) {
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(propagation)
                .build();
        this.deadlines = deadlines;
    }

    public record CreatePaymentRequest(
            String merchantId,
            long amountMinor,
            String currency,
            String cardToken,
            String cardBin,
            String cardLast4,
            String merchantReference) {
    }

    public record PaymentResponse(
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

    public PaymentResponse create(CreatePaymentRequest request) {
        return deadlines.callWithin("orchestrator.create", () -> {
            try {
                PaymentResponse response = client.post()
                        .uri("/internal/v1/payments")
                        .body(request)
                        .retrieve()
                        .body(PaymentResponse.class);
                if (response == null) {
                    throw new OrchestratorUnavailableException(null);
                }
                return response;
            } catch (RestClientException e) {
                throw new OrchestratorUnavailableException(e);
            }
        });
    }

    public Optional<PaymentResponse> find(String paymentId) {
        return deadlines.callWithin("orchestrator.find", () -> findInternal(paymentId));
    }

    private Optional<PaymentResponse> findInternal(String paymentId) {
        try {
            return Optional.ofNullable(client.get()
                    .uri("/internal/v1/payments/{id}", paymentId)
                    .retrieve()
                    .body(PaymentResponse.class));
        } catch (HttpClientErrorException.NotFound e) {
            // A 404 is an answer, not a failure. Translating it here keeps the
            // controller from having to know about HTTP status codes at all.
            return Optional.empty();
        } catch (RestClientException e) {
            throw new OrchestratorUnavailableException(e);
        }
    }

    public static class OrchestratorUnavailableException extends RuntimeException {

        public OrchestratorUnavailableException(Throwable cause) {
            super("no usable response from payment-orchestrator", cause);
        }
    }
}
