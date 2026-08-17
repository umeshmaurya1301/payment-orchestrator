package com.payorch.orchestrator.routing;

import java.util.Set;

/**
 * Whether a failed authorization may be re-attempted <strong>on a different
 * provider</strong>.
 *
 * <p>This is the single highest-consequence rule in the system, and it is
 * deliberately a class of its own rather than an {@code if} inside
 * {@code PaymentService}, so that it can be stated once, tested directly, and
 * cited in a code review.
 *
 * <h2>The rule</h2>
 *
 * <p><strong>Fail over only on errors that prove the request was never
 * processed. Never on an ambiguous one.</strong>
 *
 * <p>The reasoning is the difference between retrying and rerouting, and it is
 * easy to miss because they look like the same operation:
 *
 * <ul>
 *   <li>A <strong>retry to the same provider</strong> is safe whenever the
 *       original idempotency reference is reused, because the provider
 *       recognises it and returns the original authorization instead of creating
 *       a second one. That is {@code RETRY_WITH_SAME_REFERENCE}, and phase 3b
 *       relies on it.</li>
 *   <li>A <strong>failover to a different provider</strong> gets none of that
 *       protection. An idempotency key is only meaningful inside the namespace
 *       of the provider that issued it; psp-b has never heard of the reference
 *       psp-a was given and will happily authorize the card a second time.</li>
 * </ul>
 *
 * <p>So the two decisions read the same classification and map it differently.
 * Retry accepts {@code SAFE} and {@code RETRY_WITH_SAME_REFERENCE}; failover
 * accepts <strong>{@code SAFE} alone</strong>. Everything ambiguous goes to
 * {@code UNKNOWN} and is resolved by phase 8's poller asking the provider what
 * actually happened - which is the only way to find out, because guessing is
 * how a customer is charged twice and it is found at settlement.
 *
 * <h2>Why an allowlist of error codes</h2>
 *
 * <p>The set below is the complete list of ways this system can know that
 * nothing was sent. It is an allowlist for the same reason
 * {@code FailureClassifier} defaults to {@code NONE}: a denylist fails open, and
 * a failover that fails open is a double charge. A new error code added
 * elsewhere in the system is, by default, not eligible - which is the safe
 * direction to be wrong in.
 *
 * <p>{@code PaymentServiceFailoverTest} asserts that no code used on an
 * {@code UNKNOWN} path appears here, so the two lists cannot drift into
 * agreement by accident.
 */
public final class FailoverPolicy {

    /**
     * Errors that prove the request never reached the provider.
     *
     * <ul>
     *   <li>{@code circuit_open} - 3c's breaker refused before dialling. The
     *       provider has not seen a byte.</li>
     *   <li>{@code deadline_exceeded} - 3a's budget was already spent when the
     *       call was reached, so nothing was attempted.
     *       Note this is the <em>not started</em> case; the abandoned-in-flight
     *       case carries {@code deadline_abandoned} and is not here.</li>
     *   <li>{@code rate_limited} - our own egress limiter refused. We declined
     *       to send it, so nobody received it.</li>
     *   <li>{@code bulkhead_full} - the concurrency limit shed the call before
     *       it was dispatched.</li>
     * </ul>
     *
     * <p>Conspicuously absent: {@code connector_unavailable} and
     * {@code deadline_abandoned}. Both mean the request went out and no answer
     * came back, which is the exact shape of a lost response to a successful
     * authorization.
     *
     * <p>Also absent, and for a different reason: a <strong>decline</strong>.
     * A declined payment is not a failure at all - the provider answered
     * correctly and the answer was no. Failing that over to a second provider is
     * how one refusal becomes a card-issuer fraud flag, and it is worth stating
     * explicitly because a decline does land in {@code FAILED} alongside the
     * genuinely-not-sent cases.
     */
    private static final Set<String> NOTHING_WAS_SENT = Set.of(
            "circuit_open",
            "deadline_exceeded",
            "rate_limited",
            "bulkhead_full");

    /**
     * How many providers a single payment may be offered to in total.
     *
     * <p>3, so a payment can be tried on at most two alternatives. The bound is
     * not really about cost: every failover here is provably safe, so a hundred
     * would charge nobody twice. It is about latency and blast radius. A
     * cascading failover during a broad outage turns one slow payment into N
     * slow payments and applies the surge to every remaining provider at once -
     * which is how a partial outage becomes a total one.
     *
     * <p>The deadline bounds it too, and independently: {@code ConnectorClient}
     * refuses to start a call that cannot finish inside the remaining budget, so
     * a payment that has spent its time fails over zero further times regardless
     * of this number.
     */
    public static final int MAX_PROVIDERS_PER_PAYMENT = 3;

    private FailoverPolicy() {
    }

    /**
     * @param errorCode the code recorded against the failed attempt
     * @return true only when the request provably never reached the provider
     */
    public static boolean mayFailOver(String errorCode) {
        return errorCode != null && NOTHING_WAS_SENT.contains(errorCode);
    }

    /** Exposed so tests can assert the two lists do not overlap. */
    public static Set<String> nothingWasSentCodes() {
        return NOTHING_WAS_SENT;
    }
}
