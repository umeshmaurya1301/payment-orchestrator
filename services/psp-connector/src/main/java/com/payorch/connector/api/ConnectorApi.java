package com.payorch.connector.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The contract between {@code payment-orchestrator} and this service.
 *
 * <p>Note what the request cannot express: there is no field for a card number.
 * The orchestrator holds a vault token and two display fragments, so the
 * tokenization boundary is enforced by the shape of the type rather than by a
 * rule someone has to remember.
 *
 * <p>Phase 9 re-expresses exactly this contract as protobuf and benchmarks the
 * two under identical load. Keeping it small and explicit now is what makes that
 * comparison about the transport rather than about two different designs.
 */
public final class ConnectorApi {

    private ConnectorApi() {
    }

    /**
     * @param reference the orchestrator's attempt identifier, passed to the
     *        provider as its idempotency key. Reusing it on a retry is what
     *        makes the retry safe; changing it is what causes a double charge.
     */
    public record AuthorizeRequest(
            @NotBlank @Size(max = 64) String reference,
            @NotBlank @Size(max = 32) String pspId,
            @Min(1) long amountMinor,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotBlank @Size(max = 48) String cardToken,
            @NotBlank @Size(min = 6, max = 6) String cardBin,
            @NotBlank @Size(min = 4, max = 4) String cardLast4) {
    }

    public record AuthorizeResponse(
            String providerRef,
            Outcome outcome,
            String errorCode,
            String authCode) {
    }

    /**
     * Take the money on an authorization the provider already holds.
     *
     * <p>No card token. A capture references the provider's own handle, so the
     * vault is never opened for one - the tokenization boundary is narrower for
     * capture than for authorize, and the type says so.
     *
     * @param providerRef the handle from the authorize response. Also the
     *        idempotency key.
     */
    public record CaptureRequest(
            @NotBlank @Size(max = 64) String providerRef,
            @NotBlank @Size(max = 32) String pspId,
            @Min(1) long amountMinor) {
    }

    public record CaptureResponse(
            String providerRef,
            Outcome outcome,
            String errorCode,
            long capturedAmountMinor) {
    }

    /**
     * Give a capture back, in full. Phase 6k.
     *
     * <p>No amount and no card token. The saga is undoing one capture, and the
     * capture's own handle names it completely.
     *
     * <p>Not merchant-facing, and the {@code /internal} prefix is doing real
     * work for this one. A merchant-initiated refund is a different feature with
     * different rules - it is partial, repeatable, has its own idempotency key,
     * and answers to a customer rather than to a failed message. This endpoint
     * exists so a saga can undo its own step, and nothing else should ever call
     * it.
     */
    public record ReverseRequest(
            @NotBlank @Size(max = 64) String providerRef,
            @NotBlank @Size(max = 32) String pspId) {
    }

    public record ReverseResponse(
            String providerRef,
            Outcome outcome,
            String errorCode,
            long reversedAmountMinor) {
    }

    /**
     * Only the two answers a provider can actually give.
     *
     * <p>There is deliberately no {@code UNKNOWN} member here. A response that
     * arrives is by definition known; not receiving one is the absence of a
     * response, and it is signalled by an HTTP error rather than by a body
     * claiming uncertainty. The orchestrator's state machine is where
     * {@code UNKNOWN} lives, because that is where the distinction has
     * consequences.
     */
    public enum Outcome {
        APPROVED,
        DECLINED
    }
}
