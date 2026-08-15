package com.payorch.edge.api;

import java.time.Instant;

import com.payorch.infra.logging.MaskStrategy;
import com.payorch.infra.logging.Sensitive;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The merchant-facing contract. The only place in the system where a card
 * number is a valid input.
 */
public final class EdgeApi {

    private EdgeApi() {
    }

    public record CreatePaymentRequest(
            @Min(1) long amountMinor,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO currency code")
            String currency,
            @NotNull @Valid Card card,
            @Size(max = 128) String merchantReference) {
    }

    /**
     * A card, on its way in and no further.
     *
     * <p>Both {@code number} and {@code cvv} carry {@link Sensitive}, which is
     * safe here precisely because this record is never serialized outbound. It
     * is deserialized from the merchant's request, converted into a vault token
     * within a few statements, and discarded. Anything that does serialize it -
     * a log line, an error payload, a debugger - gets the masked form.
     *
     * <p><strong>The CVV goes no further than this object.</strong> It is not
     * stored, not hashed, not encrypted and not forwarded: the downstream
     * contract carries {@code bin + token + last4} and has no field to put it
     * in. That is a real constraint with a real cost - a production integration
     * would need the CVV at authorization time, and real vaults hold it under a
     * short TTL to make that work. Phase 1 does not, because "never stored" is
     * the property being demonstrated and a TTL is still storage.
     */
    public record Card(
            @Sensitive(MaskStrategy.PAN) @NotBlank @Size(min = 13, max = 25) String number,
            @Min(1) @Max(12) int expiryMonth,
            @Min(2000) @Max(2100) int expiryYear,
            @Sensitive @NotBlank @Pattern(regexp = "\\d{3,4}") String cvv) {
    }

    /**
     * What the merchant gets back.
     *
     * <p>Deliberately narrower than the orchestrator's own view: no
     * {@code pspId}, no {@code providerRef}. Which acquirer was used and what it
     * called the transaction are internal routing details, and exposing them
     * turns a private implementation choice into something a merchant may come
     * to depend on. Phase 5 changes provider selection on every request; that
     * has to stay possible.
     */
    public record PaymentResponse(
            String id,
            String state,
            long amountMinor,
            String currency,
            String cardBin,
            String cardLast4,
            String merchantReference,
            Instant createdAt) {
    }
}
