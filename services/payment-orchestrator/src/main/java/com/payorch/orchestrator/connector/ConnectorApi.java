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

    /**
     * Ask every provider whether it has our reference. Phase 8a.
     *
     * <p>Carries no {@code pspId}, unlike every other request here, and that is
     * the point rather than an omission: this is asked precisely when the
     * orchestrator does not know which provider has the payment - or whether
     * any of them does.
     */
    public record LookupRequest(String reference) {
    }

    /**
     * @param claimedBy the provider that holds it, or null
     * @param silent    providers that failed, timed out, or were skipped because
     *                  their breaker was open. <strong>Read this before acting
     *                  on an absent claim.</strong> Empty {@code claimedBy} with
     *                  a non-empty {@code silent} means "the providers that
     *                  answered do not have it", which is a much weaker
     *                  statement than "nobody has it" - and failing a payment on
     *                  the weaker one would abandon a customer's money
     */
    public record LookupResponse(
            String reference,
            String claimedBy,
            Outcome outcome,
            boolean captured,
            boolean reversed,
            long amountMinor,
            java.util.List<String> answered,
            java.util.List<String> silent) {

        /** True only when every provider was asked, answered, and said no. */
        public boolean definitivelyAbsent() {
            return claimedBy == null
                    && silent != null && silent.isEmpty()
                    && answered != null && !answered.isEmpty();
        }
    }

    public enum Outcome {
        APPROVED,
        DECLINED
    }
}
