package com.payorch.mockpsp.api;

import java.time.Instant;
import java.util.List;

import com.payorch.infra.logging.MaskStrategy;
import com.payorch.infra.logging.Sensitive;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The wire contract of the simulated provider.
 *
 * <p>Shaped like a real acquirer's API rather than like our domain: the provider
 * knows about a card, an amount and its own reference, and nothing about
 * payments, merchants or state machines. Keeping that separation is what makes
 * {@code psp-connector} an adapter rather than a pass-through.
 */
public final class ProviderApi {

    private ProviderApi() {
    }

    /**
     * <p>This is the one request in the system that legitimately carries a raw
     * card number: {@code psp-connector} detokenizes immediately before sending
     * it, because a provider cannot charge a token it did not issue.
     *
     * <p>{@code pan} is annotated so that anything which serializes this record -
     * a log line, an error payload, a debugger's toString - masks it. The
     * annotation is safe <em>here</em> because the simulator only ever
     * deserializes this type. The matching outbound record in
     * {@code psp-connector} deliberately carries no annotation, and there is a
     * test there explaining why.
     *
     * @param reference the caller's own identifier for this authorization. The
     *        provider treats it as an idempotency key: the same reference twice
     *        returns the same authorization, unless {@code duplicateRate} says
     *        otherwise.
     */
    public record AuthorizeRequest(
            @NotBlank @Size(max = 64) String reference,
            @Min(1) long amountMinor,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @Sensitive(MaskStrategy.PAN) @NotBlank String pan,
            @Min(1) int expiryMonth,
            @Min(2000) int expiryYear) {
    }

    public record AuthorizeResponse(
            String providerRef,
            String reference,
            Outcome outcome,
            String errorCode,
            String authCode,
            long amountMinor,
            String currency,
            String last4,
            Instant createdAt) {
    }

    public record CaptureRequest(@NotBlank String providerRef, @Min(1) long amountMinor) {
    }

    public record CaptureResponse(
            String providerRef,
            Outcome outcome,
            String errorCode,
            long capturedAmountMinor) {
    }

    /** Everything the provider will admit to knowing about one authorization. */
    public record StatusResponse(
            String providerRef,
            String reference,
            Outcome outcome,
            String errorCode,
            long amountMinor,
            String currency,
            String last4,
            boolean captured,
            Instant createdAt) {
    }

    /**
     * All authorizations the provider holds for one reference.
     *
     * <p>Exists so a duplicate is <em>observable</em>. Under
     * {@code duplicateRate}, one reference ends up with two provider references,
     * and that is a double charge. A caller that can only ask "what happened to
     * providerRef X" can never see it.
     */
    public record ReferenceView(String reference, List<StatusResponse> authorizations) {
    }

    public enum Outcome {
        APPROVED,
        DECLINED
    }
}
