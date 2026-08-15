package com.payorch.orchestrator.connector;

import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
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

    public ConnectorClient(String baseUrl, DeadlinePropagation propagation, DeadlineExecutor deadlines) {
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                // Puts the remaining budget on the wire, so the connector knows
                // how long it has rather than assuming it has forever.
                .requestInterceptor(propagation)
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
}
