package com.payorch.orchestrator.connector;

import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls {@code psp-connector} over REST.
 *
 * <p><strong>REST first, on purpose.</strong> Phase 9 migrates this exact call
 * path to gRPC and benchmarks the two under identical load. That comparison only
 * exists if the REST version is built first, measured, and kept - which is a
 * concrete data point rather than an opinion about protobuf.
 *
 * <p><strong>Phase 3a: bounded by the deadline budget.</strong> Still no retry
 * and no breaker - those are 3b and 3c, each after its own measurement. What
 * this call now has is an upper bound derived from the remaining budget rather
 * than from a number someone picked per hop, and a real abort when that bound is
 * reached.
 */
public class ConnectorClient {

    private final RestClient client;
    private final DeadlineExecutor deadlines;
    private final ObservationRegistry observations;

    public ConnectorClient(String baseUrl, DeadlinePropagation propagation, DeadlineExecutor deadlines,
                           ObservationRegistry observations) {
        this.observations = observations;
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                // Puts the remaining budget on the wire, so the connector knows
                // how long it has rather than assuming it has forever.
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

    /**
     * @throws ConnectorUnavailableException when no usable answer came back.
     *         This is the {@code UNKNOWN} path: a connection failure, a hang
     *         that eventually gave up, or the connector's own 502 saying the
     *         provider did not answer. In every one of those the card may
     *         already have been charged.
     */
    public ConnectorApi.AuthorizeResponse authorize(ConnectorApi.AuthorizeRequest request) {
        // The DeadlineExceededException this can throw is deliberately NOT
        // caught here. PaymentService needs its `wasStarted` flag to decide
        // between FAILED and UNKNOWN, and wrapping it in
        // ConnectorUnavailableException would erase exactly that distinction.
        return deadlines.callWithin("connector.authorize", () -> {
            try {
                ConnectorApi.AuthorizeResponse response = client.post()
                        .uri("/internal/v1/authorize")
                        .body(request)
                        .retrieve()
                        .body(ConnectorApi.AuthorizeResponse.class);

                if (response == null || response.outcome() == null) {
                    throw new ConnectorUnavailableException(null);
                }
                return response;
            } catch (HttpServerErrorException e) {
                // 503 from the connector means its breaker is open and nothing
                // was sent. That is a definite non-event, not an unknown.
                if (e.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) {
                    throw new ConnectorRejectedException(e);
                }
                throw new ConnectorUnavailableException(e);
            } catch (RestClientException e) {
                throw new ConnectorUnavailableException(e);
            }
        });
    }

    /**
     * Phase 6j. Take the money.
     *
     * <p>Same two exceptions and the same meanings, and the stakes are higher on
     * one of them. {@code ConnectorRejectedException} still means nothing was
     * sent, so the capture simply has not happened yet and can be retried
     * freely. {@code ConnectorUnavailableException} means the money MAY have
     * moved with no record of it here - the {@code UNKNOWN} shape, applied to an
     * operation that debits a customer rather than one that holds a balance.
     */
    public ConnectorApi.CaptureResponse capture(ConnectorApi.CaptureRequest request) {
        return deadlines.callWithin("connector.capture", () -> {
            try {
                ConnectorApi.CaptureResponse response = client.post()
                        .uri("/internal/v1/capture")
                        .body(request)
                        .retrieve()
                        .body(ConnectorApi.CaptureResponse.class);

                if (response == null || response.outcome() == null) {
                    throw new ConnectorUnavailableException(null);
                }
                return response;
            } catch (HttpServerErrorException e) {
                if (e.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) {
                    throw new ConnectorRejectedException(e);
                }
                throw new ConnectorUnavailableException(e);
            } catch (RestClientException e) {
                throw new ConnectorUnavailableException(e);
            }
        });
    }

    /** No usable response. Deliberately not the same thing as a decline. */
    public static class ConnectorUnavailableException extends RuntimeException {

        public ConnectorUnavailableException(Throwable cause) {
            super("no usable response from psp-connector", cause);
        }
    }

    /**
     * The connector refused to send the request at all.
     *
     * <p>Its circuit breaker is open. The provider was not contacted, so unlike
     * {@link ConnectorUnavailableException} this is a definite non-event: the
     * payment is {@code FAILED} and a merchant may retry it freely.
     */
    public static class ConnectorRejectedException extends RuntimeException {

        public ConnectorRejectedException(Throwable cause) {
            super("psp-connector refused the request; its circuit is open", cause);
        }
    }
}
