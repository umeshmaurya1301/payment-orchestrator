package com.payorch.connector.provider;

import com.payorch.infra.tokenization.DetokenizedCard;

/**
 * One payment provider, behind one interface.
 *
 * <p>The card arrives as an argument rather than being fetched inside the
 * implementation. That keeps detokenization on a single call path in
 * {@link com.payorch.connector.AuthorizationService}, where it can be audited,
 * instead of letting every future adapter reach into the vault for itself.
 */
public interface PspAdapter {

    /** The identifier used in {@code psp_config.psp_id} and in routing decisions. */
    String pspId();

    /**
     * @param command  everything the provider needs that is not the card
     * @param card     valid only for the duration of this call. Implementations
     *                 must not store, log or return it.
     * @throws ProviderUnavailableException when no answer was received. The
     *         distinction from a returned decline is the whole reason this
     *         exception exists.
     */
    ProviderAuthorization authorize(AuthorizeCommand command, DetokenizedCard card);

    /**
     * Takes the money that {@link #authorize} put a hold on.
     *
     * <p><strong>No {@code DetokenizedCard} argument, and that is the point.</strong>
     * A capture references an authorization the provider already holds, so the
     * card number is not needed and the vault is not touched. The
     * detokenization boundary that {@code AuthorizationService} guards has
     * exactly one caller, and capture is not one of them - visible in the
     * signature rather than asserted in a comment.
     *
     * @throws ProviderUnavailableException when no answer was received, which
     *         for a capture is worse than for an authorize: the money may have
     *         moved and nothing here knows
     */
    ProviderCapture capture(CaptureCommand command);

    record AuthorizeCommand(String reference, long amountMinor, String currency) {
    }

    /**
     * @param providerRef the handle the provider gave us at authorization time.
     *        Also the idempotency key: capturing the same reference twice must
     *        not take the money twice. The simulator gets that right by
     *        REPLACING the capture rather than accumulating it; a real acquirer
     *        generally wants an explicit idempotency key on the capture as well,
     *        and this design would need one before it met a real one.
     */
    record CaptureCommand(String providerRef, long amountMinor) {
    }

    record ProviderCapture(String providerRef, boolean captured, String errorCode,
                           long capturedAmountMinor) {
    }

    record ProviderAuthorization(String providerRef, boolean approved, String errorCode, String authCode) {
    }

    /**
     * The provider did not answer, or answered with a failure that says nothing
     * about the payment.
     *
     * <p>Deliberately distinct from a decline. A decline is an answer: the
     * payment definitively did not happen. This is the absence of an answer, and
     * the payment may well have succeeded at the provider - which is why it
     * becomes {@code UNKNOWN} upstream rather than {@code FAILED}, and why phase
     * 8 needs a status poller to resolve it.
     */
    class ProviderUnavailableException extends RuntimeException {

        public ProviderUnavailableException(String pspId, Throwable cause) {
            super("no usable response from provider " + pspId, cause);
        }
    }
}
