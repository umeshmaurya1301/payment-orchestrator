package com.payorch.orchestrator.connector;

/**
 * The connector's wire contract, as this service sees it.
 *
 * <p>A deliberate second copy of the records that {@code psp-connector}
 * publishes, rather than a shared module. Two services sharing a DTO jar share a
 * release cycle: neither can change a field without coordinating a version bump,
 * and the "shared" module accretes every type either side ever needed. The
 * duplication here is a handful of lines, and it is what lets the two services
 * evolve and deploy independently.
 *
 * <p>The cost is real and worth stating: nothing checks that these two records
 * still agree. Phase 9's protobuf definitions become that check, which is one of
 * the reasons that migration is worth doing.
 */
public final class ConnectorApi {

    private ConnectorApi() {
    }

    public record AuthorizeRequest(
            String reference,
            String pspId,
            long amountMinor,
            String currency,
            String cardToken,
            String cardBin,
            String cardLast4) {
    }

    public record AuthorizeResponse(
            String providerRef,
            Outcome outcome,
            String errorCode,
            String authCode) {
    }

    /** Phase 6j. No card token: a capture references the provider's own handle. */
    public record CaptureRequest(
            String providerRef,
            String pspId,
            long amountMinor) {
    }

    public record CaptureResponse(
            String providerRef,
            Outcome outcome,
            String errorCode,
            long capturedAmountMinor) {
    }

    /**
     * Phase 6k. Give the capture back. No amount: a compensation undoes one
     * action in full, and the action's own handle names it completely.
     */
    public record ReverseRequest(
            String providerRef,
            String pspId) {
    }

    public record ReverseResponse(
            String providerRef,
            Outcome outcome,
            String errorCode,
            long reversedAmountMinor) {
    }

    public enum Outcome {
        APPROVED,
        DECLINED
    }
}
