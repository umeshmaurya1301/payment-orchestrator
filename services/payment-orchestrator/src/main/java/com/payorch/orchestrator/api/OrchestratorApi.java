package com.payorch.orchestrator.api;

import java.time.Instant;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The contract between {@code payments-edge} and this service.
 *
 * <p>The edge has already tokenized by the time it calls here, so there is no
 * card number to accept and no field that could carry one. Everything below the
 * edge is expressed in tokens.
 */
public final class OrchestratorApi {

    private OrchestratorApi() {
    }

    public record CreatePaymentRequest(
            @NotBlank String merchantId,
            @Min(1) long amountMinor,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotBlank @Size(max = 48) String cardToken,
            @NotBlank @Size(min = 6, max = 6) String cardBin,
            @NotBlank @Size(min = 4, max = 4) String cardLast4,
            @Size(max = 128) String merchantReference) {
    }

    /**
     * @param state the payment state machine's current state, as a string. The
     *        caller branches on it, so it is part of the contract - renaming a
     *        member of the enum is a breaking API change, not a refactor.
     * @param providerRef the provider's own identifier for the latest attempt.
     *        Null while UNKNOWN, which is exactly the situation phase 8's poller
     *        has to work without.
     */
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
}
