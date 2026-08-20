package com.payorch.orchestrator.recon;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.payorch.infra.logging.LogEvent;
import com.payorch.infra.logging.LogFields;
import com.payorch.infra.resilience.lock.RedisLock;
import com.payorch.orchestrator.PaymentPersistence;
import com.payorch.orchestrator.connector.ConnectorApi;
import com.payorch.orchestrator.connector.ConnectorClient;
import com.payorch.orchestrator.domain.Payment;
import com.payorch.orchestrator.domain.PaymentAttempt;
import com.payorch.orchestrator.domain.PaymentState;

/**
 * Closes the {@code UNKNOWN} loop. Phase 8a.
 *
 * <h2>What has been true until now</h2>
 *
 * <p>Phase 3a created {@code UNKNOWN} because a connector timeout does not mean
 * a payment failed - the provider may have authorized the card and lost the
 * response coming back. That was the right call and it has been half a design
 * ever since: five phases of putting payments into a state that nothing ever
 * took them out of. Experiment 01 alone produced 2,372 of them.
 *
 * <p>A payment sitting in {@code UNKNOWN} is money that may have left a
 * customer's account with nothing in this system prepared to say so. The state
 * bought time; this job is what the time was for.
 *
 * <h2>The safe question</h2>
 *
 * <p>The resolution is not a retry and must never become one. Retrying an
 * authorization whose outcome is unknown is exactly the double charge the state
 * exists to prevent. What happens instead is a <strong>read-only lookup</strong>
 * - "have you ever seen this reference?" - fanned out to every provider, because
 * phase 5's failover means the payment may not be where this service last
 * thought it was.
 *
 * <p>Three answers, three actions:
 *
 * <ul>
 *   <li><strong>Somebody has it, approved.</strong> Resolve to
 *       {@code AUTHORIZED}. The money moved; now the books agree.</li>
 *   <li><strong>Somebody has it, declined.</strong> Resolve to {@code FAILED}.
 *       A definitive negative from the provider that holds it.</li>
 *   <li><strong>Everybody answered and nobody has it.</strong> Resolve to
 *       {@code FAILED} - the request never landed anywhere.</li>
 * </ul>
 *
 * <p>And the fourth case, which is the one that needs care: <strong>somebody did
 * not answer.</strong> That is not a "no". Failing a payment because a provider
 * was slow would abandon a charge that may well have gone through, so the
 * payment stays {@code UNKNOWN} and is asked again later. See
 * {@link ConnectorApi.LookupResponse#definitivelyAbsent()}.
 *
 * <h2>Why there is a give-up bound</h2>
 *
 * <p>Phase 8's trap list: <em>"a status poller without a give-up state - payments
 * stuck in UNKNOWN forever, polled forever, is a slow-motion outage"</em>. A
 * provider that has genuinely lost a payment will never start answering, and an
 * unbounded poller aimed at it is a permanent load floor that grows with every
 * incident the system has ever had.
 *
 * <p>After {@code max-attempts} the payment moves to {@link PaymentState#UNRESOLVED}
 * - a different state, not a flag, so every dashboard that groups by state can
 * see it without being changed. One UNRESOLVED payment is worth waking somebody
 * for; a thousand UNKNOWNs at 2am may not be.
 *
 * <h2>Off by default</h2>
 *
 * <p>{@code payorch.recon.unknown-poller.enabled}, like the compensation
 * consumer in 6k. It needs Redis and a reachable connector, and a service that
 * refused to start without them would make every earlier experiment depend on
 * this one.
 */
@Component
@ConditionalOnProperty(name = "payorch.recon.unknown-poller.enabled", havingValue = "true")
public class UnknownResolver {

    private static final Logger log = LoggerFactory.getLogger(UnknownResolver.class);

    /**
     * The lock name. One poller across the whole deployment.
     *
     * <p>Not for correctness - see {@link RedisLock}, which is explicit that it
     * cannot provide that. Every action this job takes is idempotent: the lookup
     * is read-only, and the transitions are guarded by
     * {@code PaymentTransitions}, so a second instance resolving the same
     * payment finds it is no longer {@code UNKNOWN} and its transition throws
     * rather than double-counting anything.
     *
     * <p>What the lock buys is that four instances do not all fan out to three
     * providers about the same backlog every thirty seconds. It is a cost
     * control, and the job is written to be correct without it.
     */
    private static final String LOCK = "unknown-resolver";

    private final PaymentPersistence persistence;
    private final ConnectorClient connector;
    private final RedisLock lock;

    private final int batchSize;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Duration maxBackoff;
    private final Duration lockTtl;

    private final LongAdder polled = new LongAdder();
    private final LongAdder resolvedAuthorized = new LongAdder();
    private final LongAdder resolvedFailed = new LongAdder();
    private final LongAdder gaveUp = new LongAdder();
    private final LongAdder inconclusive = new LongAdder();
    private final LongAdder lookupFailures = new LongAdder();

    public UnknownResolver(PaymentPersistence persistence,
                           ConnectorClient connector,
                           RedisLock lock,
                           @Value("${payorch.recon.unknown-poller.batch-size:50}") int batchSize,
                           @Value("${payorch.recon.unknown-poller.max-attempts:8}") int maxAttempts,
                           @Value("${payorch.recon.unknown-poller.base-backoff-ms:60000}") long baseBackoffMs,
                           @Value("${payorch.recon.unknown-poller.max-backoff-ms:3600000}") long maxBackoffMs,
                           @Value("${payorch.recon.unknown-poller.lock-ttl-ms:120000}") long lockTtlMs) {
        this.persistence = persistence;
        this.connector = connector;
        this.lock = lock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.baseBackoff = Duration.ofMillis(baseBackoffMs);
        this.maxBackoff = Duration.ofMillis(maxBackoffMs);
        this.lockTtl = Duration.ofMillis(lockTtlMs);
    }

    /**
     * @return how many payments this tick resolved into a terminal state
     */
    @Scheduled(
            fixedDelayString = "${payorch.recon.unknown-poller.interval-ms:30000}",
            // Startup is when the connection pool is coldest and nothing is
            // warm. A payment that has been UNKNOWN for an hour can wait one
            // more minute; the first real requests cannot.
            initialDelayString = "${payorch.recon.unknown-poller.initial-delay-ms:60000}")
    public int poll() {
        return lock.runIfAcquired(LOCK, lockTtl, this::resolveBatch).orElse(0);
    }

    private int resolveBatch() {
        List<Payment> due = persistence.unknownDueForPolling(batchSize);
        if (due.isEmpty()) {
            return 0;
        }

        int resolved = 0;
        for (Payment payment : due) {
            // Each payment is handled on its own. One that throws must not
            // abandon the rest of the batch: a single unresolvable payment would
            // otherwise block every payment behind it in the ordering, forever,
            // which is the starvation the ordered query was meant to prevent.
            try {
                if (resolveOne(payment)) {
                    resolved++;
                }
            } catch (RuntimeException e) {
                log.warn("could not resolve payment {}: {}", payment.getId(), e.toString());
            }
        }
        return resolved;
    }

    /**
     * @return true if this payment reached a terminal state
     */
    private boolean resolveOne(Payment payment) {
        polled.increment();

        // The reference we sent the provider IS the attempt id - phase 1's
        // decision, and the reason this lookup is possible at all. A payment
        // whose authorization never got as far as creating an attempt has
        // nothing to ask about, because nothing was ever sent under any name.
        String reference = persistence.attemptsFor(payment.getId()).stream()
                .map(PaymentAttempt::getId)
                .map(java.util.UUID::toString)
                .reduce((first, second) -> second)
                .orElse(null);

        if (reference == null) {
            log.warn("payment {} is UNKNOWN with no attempt to ask about - giving up",
                    payment.getId());
            return giveUp(payment, "no_reference");
        }

        ConnectorApi.LookupResponse answer;
        try {
            answer = connector.lookup(new ConnectorApi.LookupRequest(reference));
        } catch (RuntimeException e) {
            // The connector itself is unreachable. Nothing was learned, so this
            // costs an attempt and a backoff - not a resolution. Counted
            // separately from an inconclusive lookup because the two say
            // different things: this one is about us, that one is about a
            // provider.
            lookupFailures.increment();
            return reschedule(payment, "connector_unavailable");
        }

        if (answer.claimedBy() != null) {
            return answer.outcome() == ConnectorApi.Outcome.APPROVED
                    ? resolveAuthorized(payment, answer)
                    : resolveFailed(payment, answer.claimedBy());
        }

        if (answer.definitivelyAbsent()) {
            // Every provider was asked, every one answered, and none of them has
            // it. The request never landed anywhere, so no money moved and
            // FAILED is the truth rather than a guess.
            return resolveFailed(payment, null);
        }

        // Somebody stayed silent. NOT a no. Ask again later.
        inconclusive.increment();
        return reschedule(payment, "providers_silent");
    }

    private boolean resolveAuthorized(Payment payment, ConnectorApi.LookupResponse answer) {
        persistence.resolveUnknownToAuthorized(payment.getId(), answer.claimedBy());
        resolvedAuthorized.increment();

        // WARN, not INFO, for a success. Every one of these is a payment that
        // was authorized and that this system did not know about - the money had
        // already moved, and the gap between then and now is a window in which
        // the books were wrong. The RATE of these is the number worth watching.
        log.warn("resolved an UNKNOWN payment - the provider had authorized it all along",
                LogEvent.event()
                        .with(LogFields.PAYMENT_ID, payment.getId().toString())
                        .with(LogFields.PSP_ID, answer.claimedBy())
                        .with(LogFields.OPERATION, "resolve")
                        .with(LogFields.STATE, PaymentState.AUTHORIZED.name())
                        .with(LogFields.PREVIOUS_STATE, PaymentState.UNKNOWN.name())
                        .with(LogFields.ATTEMPT_NO, payment.getResolutionAttempts())
                        .args());
        return true;
    }

    private boolean resolveFailed(Payment payment, String claimedBy) {
        persistence.resolveUnknownToFailed(payment.getId());
        resolvedFailed.increment();

        log.info("resolved an UNKNOWN payment to FAILED",
                LogEvent.event()
                        .with(LogFields.PAYMENT_ID, payment.getId().toString())
                        .with(LogFields.PSP_ID, claimedBy == null ? "none" : claimedBy)
                        .with(LogFields.OPERATION, "resolve")
                        .with(LogFields.STATE, PaymentState.FAILED.name())
                        .with(LogFields.OUTCOME, claimedBy == null ? "ABSENT_EVERYWHERE" : "DECLINED")
                        .with(LogFields.ATTEMPT_NO, payment.getResolutionAttempts())
                        .args());
        return true;
    }

    /**
     * Nothing was learned. Either schedule another attempt or stop.
     *
     * @return true if the payment reached {@code UNRESOLVED}, which is terminal
     *         as far as this job is concerned
     */
    private boolean reschedule(Payment payment, String reason) {
        int attemptsSoFar = payment.getResolutionAttempts() + 1;
        if (attemptsSoFar >= maxAttempts) {
            return giveUp(payment, reason);
        }

        Instant next = Instant.now().plus(backoffFor(attemptsSoFar));
        persistence.recordPollAttempt(payment.getId(), next);
        log.debug("payment {} still unresolved after {} attempts ({}), next at {}",
                payment.getId(), attemptsSoFar, reason, next);
        return false;
    }

    private boolean giveUp(Payment payment, String reason) {
        persistence.giveUpOnUnknown(payment.getId());
        gaveUp.increment();

        // ERROR, and it is the only ERROR this class logs. A payment reaching
        // UNRESOLVED is money whose fate is genuinely unknown, that no automatic
        // process will ever determine, and that a person now has to telephone a
        // provider about. If anything in this system deserves to page somebody,
        // it is this line.
        log.error("giving up on an UNKNOWN payment - a person must resolve this",
                LogEvent.event()
                        .with(LogFields.PAYMENT_ID, payment.getId().toString())
                        .with(LogFields.MERCHANT_ID, String.valueOf(payment.getMerchantId()))
                        .with(LogFields.AMOUNT_MINOR, payment.getAmountMinor())
                        .with(LogFields.CURRENCY, payment.getCurrency())
                        .with(LogFields.OPERATION, "resolve")
                        .with(LogFields.STATE, PaymentState.UNRESOLVED.name())
                        .with(LogFields.ATTEMPT_NO, payment.getResolutionAttempts() + 1)
                        .with(LogFields.ERROR_CODE, reason)
                        .args());
        return true;
    }

    /**
     * Exponential, capped.
     *
     * <p>The cap is what makes the give-up bound meaningful in time rather than
     * only in count. Uncapped doubling from a minute reaches four days by the
     * eighth attempt, so "eight attempts" would mean "a week", and the payment
     * would be functionally abandoned long before it was formally given up on.
     * Capped at an hour, eight attempts is a few hours - which is roughly how
     * long a provider incident lasts, and therefore roughly how long it is worth
     * waiting before admitting nobody is going to answer.
     */
    private Duration backoffFor(int attempt) {
        Duration grown = baseBackoff.multipliedBy(1L << Math.min(attempt - 1, 20));
        return grown.compareTo(maxBackoff) > 0 ? maxBackoff : grown;
    }

    public long polledCount() {
        return polled.sum();
    }

    /**
     * Payments the provider turned out to have authorized after all.
     *
     * <p>The most important number this class produces. Each one is a charge
     * that existed in the world and not in this system until the poller asked.
     */
    public long resolvedAuthorizedCount() {
        return resolvedAuthorized.sum();
    }

    public long resolvedFailedCount() {
        return resolvedFailed.sum();
    }

    /** Payments the poller stopped asking about. Every one needs a person. */
    public long gaveUpCount() {
        return gaveUp.sum();
    }

    /** Lookups where a provider stayed silent, so nothing could be concluded. */
    public long inconclusiveCount() {
        return inconclusive.sum();
    }

    /** Lookups that never reached the connector at all. */
    public long lookupFailureCount() {
        return lookupFailures.sum();
    }
}
