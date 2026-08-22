package com.payorch.orchestrator.connector;

import org.infra.resilience.deadline.DeadlineExecutor;
import org.infra.resilience.deadline.DeadlinePropagation;
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
public class RestConnectorClient implements ConnectorClient {

    private final RestClient client;
    private final DeadlineExecutor deadlines;
    private final ObservationRegistry observations;

    public RestConnectorClient(String baseUrl, DeadlinePropagation propagation, DeadlineExecutor deadlines,
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
    @Override
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
    @Override
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

    /**
     * Phase 6k. Give the capture back.
     *
     * <p>The two exceptions keep their meanings and one of them changes value.
     * {@code ConnectorRejectedException} - nothing was sent - is comparatively
     * good news here: the compensation is still owed, still possible, and the
     * saga's ladder will hold it until the provider is reachable. It is the only
     * failure on this path that a retry actually fixes.
     *
     * <p>{@code ConnectorUnavailableException} is the end of what this design can
     * do on its own. The compensation for an unrecorded capture is now itself
     * unrecorded, and no further compensating action exists - the loop does not
     * close, and phase 8's reconciliation is the only thing that can close it by
     * asking the provider what it actually did.
     */
    @Override
    public ConnectorApi.ReverseResponse reverse(ConnectorApi.ReverseRequest request) {
        return deadlines.callWithin("connector.reverse", () -> {
            try {
                ConnectorApi.ReverseResponse response = client.post()
                        .uri("/internal/v1/reverse")
                        .body(request)
                        .retrieve()
                        .body(ConnectorApi.ReverseResponse.class);

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

    /**
     * Asks every provider whether it has this reference. Phase 8a.
     *
     * <h2>Read-only, and therefore safe to call speculatively</h2>
     *
     * <p>Every other method here can move money and is guarded accordingly. This
     * one cannot - the request carries a reference and nothing else - so the
     * {@code UNKNOWN} poller may call it for a payment whose status is a
     * complete mystery without any risk of making the mystery worse. That is the
     * property that makes automatic resolution possible at all: the safe
     * question can be asked without deciding anything first.
     *
     * <h2>Still deadline-bounded</h2>
     *
     * <p>Fanning out to three providers means the answer is bounded by the
     * slowest of them, and the poller runs on a scheduler where a hung call
     * would silently stop every subsequent tick. The connector applies its own
     * per-provider deadline slice inside the fan-out; this is the outer bound on
     * the whole thing.
     */
    @Override
    public ConnectorApi.LookupResponse lookup(ConnectorApi.LookupRequest request) {
        return deadlines.callWithin("connector.lookup", () -> {
            try {
                ConnectorApi.LookupResponse response = client.post()
                        .uri("/internal/v1/lookup")
                        .body(request)
                        .retrieve()
                        .body(ConnectorApi.LookupResponse.class);

                if (response == null) {
                    throw new ConnectorUnavailableException(null);
                }
                return response;
            } catch (RestClientException e) {
                // No 503 special case, unlike reverse. There is nothing to
                // distinguish here: a lookup that did not happen is a lookup
                // that did not happen, and the poller's answer is the same
                // either way - leave the payment UNKNOWN and ask again later.
                throw new ConnectorUnavailableException(e);
            }
        });
    }
}
