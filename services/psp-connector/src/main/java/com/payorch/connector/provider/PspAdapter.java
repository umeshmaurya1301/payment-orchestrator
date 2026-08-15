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

    record AuthorizeCommand(String reference, long amountMinor, String currency) {
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
