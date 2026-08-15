package com.payorch.infra.resilience.retry;

/**
 * Whether a failure may be retried, and under what condition.
 *
 * <p><strong>This enum exists before the retry mechanism does, deliberately.</strong>
 * The phase-3 plan puts classification first and the retry second, and the
 * ordering is the whole safety argument: a retry loop written first will be
 * given a classification that justifies it, whereas a classification written
 * first constrains what the retry loop is allowed to do.
 *
 * <p>The distinction that matters for a payment is not "was it an error" but
 * "did the provider see it". Those are different questions and only the second
 * one decides whether retrying risks charging a card twice.
 */
public enum FailureClass {

    /**
     * The request provably never reached the provider.
     *
     * <p>A connection refused before a byte was written, a DNS failure, or a
     * deadline that expired before the call was started. Nothing was processed,
     * so a retry cannot duplicate anything and needs no cooperation from the
     * provider.
     *
     * <p>This is the only class that is safe to retry with no further
     * conditions, and it is a much smaller set than it first appears.
     */
    SAFE,

    /**
     * The provider may have processed the request. Retrying is safe
     * <strong>only</strong> if the original idempotency reference is reused.
     *
     * <p>A read timeout, a connection reset mid-exchange, a 5xx, a deadline
     * abandoned in flight. In every one of these the request may have been
     * received, acted on, and the response lost on the way back - which is
     * precisely the situation {@code UNKNOWN} exists to describe.
     *
     * <p>Retrying <em>with the same reference</em> is safe because the provider
     * recognises it and returns the original authorization instead of creating
     * a second. Retrying with a fresh reference is a double charge. That is the
     * entire reason phase 1 made the attempt id the provider's idempotency key
     * and phase 3 must not quietly generate a new one.
     */
    RETRY_WITH_SAME_REFERENCE,

    /**
     * Retrying changes nothing, or makes things worse.
     *
     * <p>Two quite different situations, which is why they share a class:
     *
     * <ul>
     *   <li><strong>A definite answer.</strong> The provider declined. That is a
     *       business outcome, not a fault, and retrying a decline is how a
     *       system turns one refusal into a card-issuer fraud flag.</li>
     *   <li><strong>A permanent error.</strong> A malformed request, an unknown
     *       token, a rejected credential. The next attempt is identical, so it
     *       will fail identically - and it will consume budget that a
     *       recoverable failure could have used.</li>
     * </ul>
     */
    NONE
}
