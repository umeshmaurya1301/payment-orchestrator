package com.payorch.orchestrator;

import java.util.Optional;
import java.util.UUID;

import com.payorch.infra.web.ApiException;
import com.payorch.orchestrator.domain.Payment;
import com.payorch.orchestrator.domain.PaymentAttempt;
import com.payorch.orchestrator.domain.PaymentAttemptRepository;
import com.payorch.orchestrator.domain.PaymentRepository;
import com.payorch.orchestrator.domain.PaymentState;
import com.payorch.orchestrator.domain.PspConfig;
import com.payorch.orchestrator.domain.PspConfigRepository;
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

    public PaymentPersistence(PaymentRepository payments,
                              PaymentAttemptRepository attempts,
                              PspConfigRepository pspConfigs,
                              PaymentOutcomeMetrics outcomes) {
        this.payments = payments;
        this.attempts = attempts;
        this.pspConfigs = pspConfigs;
        this.outcomes = outcomes;
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
     * <p>Routing lives here in phase 1 because there is nothing to route on but
     * a priority column. Phase 5 moves the decision into {@code psp-router},
     * where it is made from observed provider health.
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
        Payment payment = require(paymentId);

        Optional<PspConfig> route = pspConfigs.findByEnabledTrueOrderByPriorityAsc().stream()
                .filter(config -> config.supports(payment.getCurrency()))
                .findFirst();
        if (route.isEmpty()) {
            return Optional.empty();
        }
        PspConfig chosen = route.get();

        payment.assignPsp(chosen.getPspId());
        payment.transitionTo(PaymentState.ROUTED);

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
        return payment;
    }

    @Transactional
    public Payment recordApproved(UUID paymentId, UUID attemptId, String providerRef, long latencyMs) {
        PaymentAttempt attempt = attempts.findById(attemptId).orElseThrow();
        attempt.succeeded(providerRef, latencyMs);

        Payment payment = require(paymentId);
        payment.transitionTo(PaymentState.AUTHORIZED);
        outcomes.record(PaymentState.AUTHORIZED);
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
        return payment;
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
