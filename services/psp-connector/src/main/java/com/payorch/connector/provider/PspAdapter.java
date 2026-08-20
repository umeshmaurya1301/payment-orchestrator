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

    /**
     * Gives back a capture, in full. The saga's compensating action.
     *
     * <p>No {@code DetokenizedCard} here either, for the same reason as
     * {@link #capture} - and the count is now the claim worth making: this
     * interface has three money-moving operations and exactly ONE of them opens
     * the vault. That is not an accident of who wrote which method. A card
     * number is needed to create an obligation and never to modify one, so the
     * boundary tracks a real property of payments rather than a convention this
     * codebase adopted.
     *
     * <p><strong>Must be safe to call twice.</strong> The saga retries a failed
     * compensation, and a compensation that cannot be retried fails permanently
     * on the first network hiccup. The provider - not this system - is what
     * makes that true, by recognising a reversal it has already performed and
     * answering with the original result rather than moving money again.
     *
     * @throws ProviderUnavailableException when no answer was received. Worse
     *         here than anywhere else in this interface: the compensation for a
     *         capture whose outcome is unknown is a reversal whose outcome is
     *         also unknown, and there is no third action that resolves it. Phase
     *         8's reconciliation is the only way out, which is the honest limit
     *         of what a saga buys.
     */
    ProviderReversal reverse(ReverseCommand command);

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

    /**
     * @param providerRef the same handle the capture used. There is no separate
     *        reversal id, and no amount: a compensation undoes one action
     *        exactly once, so the action's own identifier is the whole of what
     *        it needs. A partial refund is a different operation and would need
     *        both.
     */
    /**
     * Asks a provider whether it has ever seen one of our references. Phase 7f.
     *
     * <p>Keyed on OUR reference rather than on the provider's, and that is the
     * whole point of the operation. If we held the provider's reference we would
     * already know the answer - the case this exists for is the payment recorded
     * {@code UNKNOWN}, where the request may or may not have arrived and no
     * providerRef ever came back. The only question left is "does anybody have
     * this?", and it has to be asked of every provider, because a failover means
     * it might not be the one we think.
     *
     * <p>Read-only by construction. There is no amount and no card - it cannot
     * move money however it is called, which is what makes it safe to fan out
     * speculatively across providers.
     */
    ProviderLookup lookup(LookupCommand command);

    record LookupCommand(String reference) {
    }

    /**
     * @param found false when the provider has no record. NOT an error: for a
     *        fan-out across three providers, two "no" answers are the expected
     *        case and the interesting one is the single "yes"
     */
    record ProviderLookup(String pspId, boolean found, String providerRef,
                          String outcome, boolean captured, boolean reversed,
                          long amountMinor) {

        public static ProviderLookup notFound(String pspId) {
            return new ProviderLookup(pspId, false, null, null, false, false, 0);
        }
    }

    record ReverseCommand(String providerRef) {
    }

    record ProviderReversal(String providerRef, boolean reversed, String errorCode,
                            long reversedAmountMinor) {
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
