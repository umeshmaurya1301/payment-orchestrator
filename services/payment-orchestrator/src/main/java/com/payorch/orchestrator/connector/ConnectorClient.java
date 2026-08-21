package com.payorch.orchestrator.connector;

/**
 * The orchestrator's view of psp-connector, independent of transport.
 *
 * <h2>Why this is an interface as of phase 9a</h2>
 *
 * <p>Phase 9 asks for a REST vs gRPC benchmark under identical load, and a
 * benchmark needs both arms runnable at once. Making the caller depend on the
 * shape of the call rather than on the wire is what lets a single environment
 * variable choose which one carries a payment - the same pattern
 * {@code EVENTS_PUBLISHER} established in phase 6, where keeping the dual-write
 * arm alive is what allowed its "before" to be re-measured rather than quoted.
 *
 * <h2>The exceptions live here, and that is the point of them</h2>
 *
 * <p>They are nested in this interface rather than in either implementation
 * because they are part of the CONTRACT, not of a transport. The distinction
 * they carry is the one phase 1 spent real effort on and phase 3a built a state
 * for:
 *
 * <ul>
 *   <li>{@link ConnectorRejectedException} - nothing was sent. The card was
 *       definitely not charged, the payment is {@code FAILED}, and a merchant
 *       may retry freely.</li>
 *   <li>{@link ConnectorUnavailableException} - the request went out and no
 *       usable answer came back. The provider may have charged the card, so the
 *       payment is {@code UNKNOWN} and phase 8a's poller has to resolve it.</li>
 * </ul>
 *
 * <p>Both transports have to preserve that distinction or the state machine
 * above them is deciding on noise. In HTTP it is 503 against 502; in gRPC there
 * is no code that means "I tried and do not know", so
 * {@code GrpcConnectorClient} has to reconstruct it - which is exactly the kind
 * of thing that is easy to get silently wrong and is why it is written down
 * here rather than in either implementation.
 */
public interface ConnectorClient {

    ConnectorApi.AuthorizeResponse authorize(ConnectorApi.AuthorizeRequest request);

    ConnectorApi.CaptureResponse capture(ConnectorApi.CaptureRequest request);

    ConnectorApi.ReverseResponse reverse(ConnectorApi.ReverseRequest request);

    ConnectorApi.LookupResponse lookup(ConnectorApi.LookupRequest request);

    /** No usable response. Deliberately not the same thing as a decline. */
    class ConnectorUnavailableException extends RuntimeException {

        public ConnectorUnavailableException(Throwable cause) {
            super("no usable response from psp-connector", cause);
        }
    }

    /**
     * The connector refused to send the request at all.
     *
     * <p>The provider was not contacted, so unlike
     * {@link ConnectorUnavailableException} this is a definite non-event: the
     * payment is {@code FAILED} and a merchant may retry it freely. That is the
     * only thing the orchestrator needs to know, and it is the same for every
     * gate that produces this.
     *
     * <p><strong>Which gate, though, matters to the person reading the log.</strong>
     * Four different things throw this — an open circuit, a full bulkhead, the
     * egress rate limiter, and a connection that was refused outright — and they
     * want four different responses from an operator. Until phase 9a this
     * exception said "its circuit is open" in all four cases, which was written
     * when the breaker was the only gate that existed and stayed there through
     * three more being added. A message that names the wrong subsystem is worse
     * than a vague one: it sends somebody to look at a breaker that is closed.
     */
    class ConnectorRejectedException extends RuntimeException {

        /** Prefer {@link #ConnectorRejectedException(String, Throwable)}. */
        public ConnectorRejectedException(Throwable cause) {
            this("without contacting the provider", cause);
        }

        public ConnectorRejectedException(String reason, Throwable cause) {
            super("psp-connector refused the request: " + reason, cause);
        }
    }
}
