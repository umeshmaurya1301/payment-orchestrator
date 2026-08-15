package com.payorch.orchestrator.connector;

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
 * <p><strong>No timeouts, no retries, no breaker.</strong> Phase 1 has no
 * resilience anywhere. This client is the second half of the call chain phase 2
 * saturates on purpose, and every defence added here before that measurement
 * erases the "before" half of the graph.
 */
public class ConnectorClient {

    private final RestClient client;

    public ConnectorClient(String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * @throws ConnectorUnavailableException when no usable answer came back.
     *         This is the {@code UNKNOWN} path: a connection failure, a hang
     *         that eventually gave up, or the connector's own 502 saying the
     *         provider did not answer. In every one of those the card may
     *         already have been charged.
     */
    public ConnectorApi.AuthorizeResponse authorize(ConnectorApi.AuthorizeRequest request) {
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
    }

    /** No usable response. Deliberately not the same thing as a decline. */
    public static class ConnectorUnavailableException extends RuntimeException {

        public ConnectorUnavailableException(Throwable cause) {
            super("no usable response from psp-connector", cause);
        }
    }
}
