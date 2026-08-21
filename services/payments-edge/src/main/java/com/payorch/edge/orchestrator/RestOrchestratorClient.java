package com.payorch.edge.orchestrator;

import java.time.Instant;
import java.util.Optional;

import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import org.springframework.web.client.HttpClientErrorException;
import io.micrometer.observation.ObservationRegistry;
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
public class RestOrchestratorClient implements OrchestratorClient {

    private final RestClient client;
    private final DeadlineExecutor deadlines;
    private final ObservationRegistry observations;

    public RestOrchestratorClient(String baseUrl, DeadlinePropagation propagation,
                                  DeadlineExecutor deadlines, ObservationRegistry observations) {
        this.observations = observations;
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(propagation)
                // Phase 4. Without this the trace stops at this service.
                //
                // Boot instruments RestClient through the container's
                // RestClient.Builder, and every client in this system is built
                // by hand with RestClient.builder() - the same reason 3a's
                // DeadlinePropagation is contributed as a bean rather than
                // through a RestClientCustomizer. A customizer would apply to
                // nothing at all, quietly, while looking like it covered
                // everything.
                //
                // The registry is what injects `traceparent` on the way out and
                // opens the client span. A missing one does not fail: it
                // produces a trace that ends at the caller and a downstream
                // service whose spans have a different trace id, which reads as
                // "the trace is broken" rather than "instrumentation is absent".
                .observationRegistry(observations)
                .build();
        this.deadlines = deadlines;
    }

    @Override
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

    /**
     * Phase 6j. Capture, proxied through.
     *
     * <p>A 409 from the orchestrator - not capturable, already captured, the
     * provider refused - is a genuine answer about the payment and is passed
     * back rather than turned into an {@code OrchestratorUnavailableException}.
     * Wrapping it would tell the merchant "we do not know what happened" about
     * a case where we know exactly what happened.
     */
    @Override
    public PaymentResponse capture(String paymentId) {
        return deadlines.callWithin("orchestrator.capture", () -> {
            try {
                PaymentResponse response = client.post()
                        .uri("/internal/v1/payments/{id}/capture", paymentId)
                        .retrieve()
                        .body(PaymentResponse.class);
                if (response == null) {
                    throw new OrchestratorUnavailableException(null);
                }
                return response;
            } catch (HttpClientErrorException e) {
                throw new CaptureRefusedException(e.getStatusCode().value(),
                        e.getResponseBodyAsString());
            } catch (RestClientException e) {
                throw new OrchestratorUnavailableException(e);
            }
        });
    }

    @Override
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

}
