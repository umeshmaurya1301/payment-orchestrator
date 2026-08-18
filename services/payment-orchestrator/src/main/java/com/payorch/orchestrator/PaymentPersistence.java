package com.payorch.orchestrator;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.payorch.infra.web.ApiException;
import com.payorch.orchestrator.domain.Payment;
import com.payorch.orchestrator.domain.PaymentAttempt;
import com.payorch.orchestrator.domain.PaymentAttemptRepository;
import com.payorch.orchestrator.domain.PaymentRepository;
import com.payorch.orchestrator.domain.PaymentState;
import com.payorch.orchestrator.domain.PspConfig;
import com.payorch.orchestrator.domain.MerchantRouting;
import com.payorch.orchestrator.domain.MerchantRoutingRepository;
import com.payorch.orchestrator.domain.PspConfigRepository;
import com.payorch.orchestrator.events.OutboxWriter;
import com.payorch.orchestrator.routing.HealthWeightedRouter;
import com.payorch.orchestrator.routing.RoutingStrategy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every database step of a payment, and nothing else.
 *
 * <p>Split out from {@link PaymentService} for one reason: <strong>no
 * transaction may be open across the provider call.</strong> A single
 * {@code @Transactional} method wrapping "save, call the connector, save the
 * result" would hold a pooled JDBC connection for the entire duration of a
 * remote call that has no timeout. Twenty connections and twenty hung calls, and
 * the database pool is exhausted by a failure that has nothing to do with the
 * database - and, worse, the pool exhaustion would be blamed for it.
 *
 * <p>This is not a resilience measure. It is a transaction boundary, and getting
 * it right is what makes phase 2's measurements attributable.
 */
@Service
public class PaymentPersistence {

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final PspConfigRepository pspConfigs;
    private final PaymentOutcomeMetrics outcomes;
    private final HealthWeightedRouter router;
    private final MerchantRoutingRepository merchants;
    private final OutboxWriter outbox;

    public PaymentPersistence(PaymentRepository payments,
                              PaymentAttemptRepository attempts,
                              PspConfigRepository pspConfigs,
                              PaymentOutcomeMetrics outcomes,
                              HealthWeightedRouter router,
                              MerchantRoutingRepository merchants,
                              OutboxWriter outbox) {
        this.payments = payments;
        this.attempts = attempts;
        this.pspConfigs = pspConfigs;
        this.outcomes = outcomes;
        this.router = router;
        this.merchants = merchants;
        this.outbox = outbox;
    }

    @Transactional
    public Payment initiate(UUID merchantId,
                            long amountMinor,
                            String currency,
                            String cardToken,
                            String cardBin,
                            String cardLast4,
                            String merchantReference) {
        return payments.save(Payment.initiate(
                merchantId, amountMinor, currency, cardToken, cardBin, cardLast4, merchantReference));
    }

    /**
     * Routes the payment and opens an attempt, in one transaction, before any
     * network call happens.
     *
     * <p>Routing lived here in phase 1 because there was nothing to route on but
     * a priority column. Phase 5 keeps the decision here and changes what it is
     * made from: {@link HealthWeightedRouter} weights the candidates by health
     * observed in {@code psp-connector}, which is the only service that can see
     * it. The candidate list - enabled, supports this currency, priority order -
     * is unchanged, and is still exactly what the router falls back to when it
     * has no health view.
     *
     * <p>Returns empty rather than throwing when nothing can be routed to, and
     * that is not stylistic. Throwing from inside a transactional method rolls
     * the transaction back, so the {@code FAILED} state the exception was
     * reporting would never be committed - the payment would be left in
     * {@code INITIATED} forever while the caller was told it had failed. The
     * caller marks it failed in {@link #markUnroutable} instead, in a
     * transaction that is allowed to commit.
     */
    @Transactional
    public Optional<PaymentAttempt> beginAuthorization(UUID paymentId) {
        return beginAuthorization(paymentId, Set.of());
    }

    /**
     * @param exclude providers this payment has already been offered to, which
     *                are skipped. Only ever non-empty on a failover, and only
     *                after {@code FailoverPolicy} has established that the
     *                previous provider never received the request.
     */
    @Transactional
    public Optional<PaymentAttempt> beginAuthorization(UUID paymentId, Set<String> exclude) {
        Payment payment = require(paymentId);

        // Phase 5. The candidate list is still "enabled, supports this currency,
        // in priority order" - what changed is that the first one no longer
        // automatically wins. The router weights them by observed health, and
        // falls back to this exact order when it has no health view.
        List<PspConfig> candidates = pspConfigs.findByEnabledTrueOrderByPriorityAsc().stream()
                .filter(config -> config.supports(payment.getCurrency()))
                .filter(config -> !exclude.contains(config.getPspId()))
                .toList();

        // 5d. The merchant's own strategy, defaulting to HEALTH_WEIGHTED when
        // the merchant row has vanished - which should not happen, and which is
        // not a reason to fail a payment that has already been accepted.
        RoutingStrategy strategy = merchants.findById(payment.getMerchantId())
                .map(MerchantRouting::getRoutingStrategy)
                .map(RoutingStrategy::parse)
                .orElse(RoutingStrategy.HEALTH_WEIGHTED);

        Optional<PspConfig> route = router.choose(candidates, strategy);
        if (route.isEmpty()) {
            return Optional.empty();
        }
        PspConfig chosen = route.get();

        payment.assignPsp(chosen.getPspId());
        // On a failover the payment is ALREADY in ROUTED - recordFailedButRoutable
        // put it there - and the machine forbids a state transitioning to itself.
        // So this transition happens only on the first attempt.
        if (payment.getState() == PaymentState.INITIATED) {
            payment.transitionTo(PaymentState.ROUTED);
        }

        // The attempt row is written BEFORE the call. If this process dies
        // mid-authorization, reconciliation in phase 8 still finds evidence that
        // a call was made, which is the difference between a resolvable UNKNOWN
        // and a payment nobody can account for.
        PaymentAttempt attempt = attempts.save(PaymentAttempt.start(
                paymentId, nextAttemptNo(paymentId), chosen.getPspId(), PaymentAttempt.Operation.AUTHORIZE));

        payment.transitionTo(PaymentState.AUTHORIZING);
        return Optional.of(attempt);
    }

    /**
     * A payment that could not be routed is {@code FAILED}, not {@code UNKNOWN}.
     * No provider was contacted, so the card demonstrably was not charged, and
     * an {@code UNKNOWN} here would send phase 8's poller looking for a
     * reference that was never issued.
     */
    @Transactional
    public Payment markUnroutable(UUID paymentId) {
        Payment payment = require(paymentId);
        payment.transitionTo(PaymentState.FAILED);
        outcomes.record(PaymentState.FAILED);
        outbox.record(payment);
        return payment;
    }

    @Transactional
    public Payment recordApproved(UUID paymentId, UUID attemptId, String providerRef, long latencyMs) {
        PaymentAttempt attempt = attempts.findById(attemptId).orElseThrow();
        attempt.succeeded(providerRef, latencyMs);

        Payment payment = require(paymentId);
        payment.transitionTo(PaymentState.AUTHORIZED);
        outcomes.record(PaymentState.AUTHORIZED);
        // Phase 6b. Inside THIS transaction, which is the whole point - see
        // OutboxWriter. Written here rather than at PaymentService's call site
        // because that call runs after this method has already committed, and an
        // outbox row written after the commit is just a slower dual write.
        outbox.record(payment);
        return payment;
    }

    @Transactional
    public Payment recordDeclined(UUID paymentId, UUID attemptId,
                                  String providerRef, String errorCode, long latencyMs) {
        PaymentAttempt attempt = attempts.findById(attemptId).orElseThrow();
        attempt.failed(providerRef, errorCode, latencyMs);

        Payment payment = require(paymentId);
        payment.transitionTo(PaymentState.FAILED);
        outcomes.record(PaymentState.FAILED);
        outbox.record(payment);
        return payment;
    }

    /**
     * Records an attempt that failed <em>without ending the payment</em>, so it
     * can be offered to another provider.
     *
     * <p>The distinction from {@link #recordDeclined} is the whole of phase 5's
     * failover safety. {@code recordDeclined} moves the payment to
     * {@code FAILED}, which is terminal - correctly, because a merchant has been
     * told the payment failed. This method leaves the payment alive, in
     * {@code ROUTED}, ready to be routed again.
     *
     * <p>It must therefore only ever be called for an error that proves the
     * provider never received the request. {@code FailoverPolicy} decides that,
     * and {@code PaymentTransitions} enforces it independently: an ambiguous
     * failure has already moved the payment to {@code UNKNOWN}, from which the
     * transition below is not permitted and would throw.
     *
     * <p>The attempt row still records its own failure. The history of which
     * providers were tried lives there, which is what the payment's single state
     * column cannot express and what phase 8's reconciliation will read.
     */
    @Transactional
    public Payment recordFailedButRoutable(UUID paymentId, UUID attemptId,
                                           String errorCode, long latencyMs) {
        PaymentAttempt attempt = attempts.findById(attemptId).orElseThrow();
        attempt.failed(null, errorCode, latencyMs);

        Payment payment = require(paymentId);
        payment.transitionTo(PaymentState.ROUTED);
        // Deliberately NOT counted in PaymentOutcomeMetrics. The payment has not
        // reached a terminal state - counting it here and again when it finally
        // resolves would make the failure rate report more outcomes than there
        // were payments.
        return payment;
    }

    /**
     * The path that exists because a timeout is not a failure.
     *
     * <p>Nothing here claims the payment did not happen. The attempt is
     * {@code UNKNOWN}, the payment is {@code UNKNOWN}, and resolving it means
     * asking the provider - not guessing, and certainly not retrying.
     */
    @Transactional
    public Payment recordUnknown(UUID paymentId, UUID attemptId, String errorCode, long latencyMs) {
        PaymentAttempt attempt = attempts.findById(attemptId).orElseThrow();
        attempt.unknown(errorCode, latencyMs);

        Payment payment = require(paymentId);
        payment.transitionTo(PaymentState.UNKNOWN);
        outcomes.record(PaymentState.UNKNOWN);
        outbox.record(payment);
        return payment;
    }

    /**
     * Phase 6j. The payment is captured: the money has actually moved.
     *
     * <p>The outbox row goes in the same transaction, exactly as
     * {@link #recordApproved} does, and for the same reason. It is worth
     * restating for capture because the consequence is different in kind: a lost
     * {@code payment.authorized} event means a ledger that is behind on a HOLD,
     * and a lost {@code payment.captured} event means a customer has been
     * charged and no ledger anywhere knows.
     *
     * <p>No attempt row is written. An attempt records a call this service made
     * to a provider on the authorization path, keyed
     * {@code (payment_id, operation, attempt_no)}; giving capture its own
     * attempt row would make {@code attemptNo} mean two different things
     * depending on which operation you were looking at, and every routing query
     * in phase 5 reads it. The provider reference is already on the authorizing
     * attempt, which is where a capture's audit trail belongs.
     */
    @Transactional
    public Payment recordCaptured(UUID paymentId) {
        Payment payment = require(paymentId);
        payment.transitionTo(PaymentState.CAPTURED);
        outcomes.record(PaymentState.CAPTURED);
        outbox.record(payment);
        return payment;
    }

    /** The attempt that produced the authorization, and therefore the providerRef. */
    @Transactional(readOnly = true)
    public Optional<PaymentAttempt> authorizedAttempt(UUID paymentId) {
        return attempts.findByPaymentIdOrderByAttemptNoAsc(paymentId).stream()
                .filter(a -> a.getProviderRef() != null)
                .reduce((first, second) -> second);
    }

    @Transactional(readOnly = true)
    public Optional<Payment> find(UUID paymentId) {
        return payments.findById(paymentId);
    }

    @Transactional(readOnly = true)
    public Optional<PaymentAttempt> latestAttempt(UUID paymentId) {
        var all = attempts.findByPaymentIdOrderByAttemptNoAsc(paymentId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.getLast());
    }

    private Payment require(UUID paymentId) {
        return payments.findById(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "payment_not_found",
                        "no payment with that id"));
    }

    /**
     * Always 1 in phase 1, because nothing retries yet.
     *
     * <p>Computed rather than hard-coded so phase 3's retry work does not have
     * to find and fix a literal - and so the unique constraint on
     * {@code (payment_id, operation, attempt_no)} has something meaningful to
     * enforce when it does.
     */
    private int nextAttemptNo(UUID paymentId) {
        return attempts.findByPaymentIdOrderByAttemptNoAsc(paymentId).stream()
                .filter(a -> a.getOperation() == PaymentAttempt.Operation.AUTHORIZE)
                .mapToInt(PaymentAttempt::getAttemptNo)
                .max()
                .orElse(0) + 1;
    }
}
