package com.payorch.orchestrator;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.payorch.infra.logging.LogEvent;
import com.payorch.infra.logging.LogFields;
import com.payorch.infra.persistence.Uuid7;
import com.payorch.infra.resilience.deadline.DeadlineExceededException;
import com.payorch.infra.web.ApiException;
import com.payorch.orchestrator.api.OrchestratorApi;
import com.payorch.orchestrator.connector.ConnectorApi;
import com.payorch.orchestrator.connector.ConnectorClient;
import com.payorch.orchestrator.domain.Payment;
import com.payorch.orchestrator.domain.PaymentAttempt;
import com.payorch.orchestrator.domain.PaymentState;
import com.payorch.orchestrator.domain.PaymentTransitions;
import com.payorch.orchestrator.events.PaymentEvents;
import com.payorch.orchestrator.routing.FailoverPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Owns every state transition a payment makes.
 *
 * <p>Concentrating that here is the point. If the edge could move a payment to
 * {@code AUTHORIZED}, or the connector could mark one {@code FAILED}, the
 * transition table would describe three services' behaviour instead of one, and
 * no single place would be able to enforce it.
 *
 * <p>The flow is synchronous. Resilience lives below it - the deadline budget
 * (3a) bounds it, and {@code psp-connector} retries (3b) behind a circuit
 * breaker (3c). What this class owns is the consequence: translating each way a
 * call can fail into the right state.
 *
 * <p>That translation is the interesting part, and every branch turns on one
 * question - <em>was the request sent?</em> Nothing sent is {@code FAILED} and a
 * merchant may retry freely; sent and unanswered is {@code UNKNOWN} and they
 * must not. Three different failures reach here and only one of them is
 * {@code UNKNOWN}.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentPersistence persistence;
    private final ConnectorClient connector;
    private final PaymentEvents events;

    public PaymentService(PaymentPersistence persistence, ConnectorClient connector,
                          PaymentEvents events) {
        this.persistence = persistence;
        this.connector = connector;
        this.events = events;
    }

    public OrchestratorApi.PaymentResponse create(OrchestratorApi.CreatePaymentRequest request) {
        UUID merchantId = Optional.ofNullable(Uuid7.parseOrNull(request.merchantId()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "invalid_merchant_id",
                        "merchantId is not a valid UUID"));

        Payment payment = persistence.initiate(
                merchantId,
                request.amountMinor(),
                request.currency(),
                request.cardToken(),
                request.cardBin(),
                request.cardLast4(),
                request.merchantReference());

        log.info("payment initiated",
                LogEvent.event()
                        .with(LogFields.PAYMENT_ID, payment.getId().toString())
                        .with(LogFields.MERCHANT_ID, merchantId.toString())
                        .with(LogFields.STATE, payment.getState().name())
                        .with(LogFields.AMOUNT_MINOR, payment.getAmountMinor())
                        .with(LogFields.CURRENCY, payment.getCurrency())
                        .with(LogFields.TOKEN, payment.getCardToken())
                        .with(LogFields.BIN, payment.getCardBin())
                        .with(LogFields.LAST4, payment.getCardLast4())
                        .args());

        return authorize(payment.getId());
    }

    public Optional<OrchestratorApi.PaymentResponse> find(UUID paymentId) {
        return persistence.find(paymentId).map(this::toResponse);
    }

    /**
     * Phase 6j. Takes the money an authorization is holding.
     *
     * <h2>The order, and why it is the opposite of the outbox's</h2>
     *
     * <p>Call the provider first, record second. That looks like the dual write
     * phase 6a spent an experiment condemning, and it is not the same shape: the
     * provider is not a second database we own, it is the authoritative record
     * of whether money moved. There is no transaction that can span it. What the
     * outbox fixed was two writes to systems we control; what remains here is
     * irreducible, and it is exactly why phase 6k needs a saga rather than a
     * bigger transaction.
     *
     * <p>So the window is real and named: the provider has taken the money and
     * this service has not yet written it down. A crash in that window leaves a
     * captured payment recorded as {@code AUTHORIZED}, which is a customer
     * charged for a payment the ledger thinks is a hold. Phase 8's
     * reconciliation is what closes it, by asking the provider what it actually
     * did - the same answer as for {@code UNKNOWN}, for the same reason.
     *
     * @throws ApiException 409 if the payment is not in a state that can be
     *         captured, which {@link PaymentTransitions} decides rather than
     *         this method
     */
    public OrchestratorApi.PaymentResponse capture(UUID paymentId) {
        Payment payment = persistence.find(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "payment_not_found",
                        "no payment with that id"));

        // Checked here as well as enforced by transitionTo, and the duplication
        // is deliberate: this one produces a 409 an operator can act on, the
        // other produces a 500 that says a bug reached production.
        if (payment.getState() != PaymentState.AUTHORIZED) {
            throw new ApiException(HttpStatus.CONFLICT, "not_capturable",
                    "only an AUTHORIZED payment can be captured; this one is "
                            + payment.getState());
        }

        PaymentAttempt authorized = persistence.authorizedAttempt(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "no_provider_reference",
                        "the payment is AUTHORIZED but carries no provider reference"));

        ConnectorApi.CaptureResponse response = connector.capture(
                new ConnectorApi.CaptureRequest(
                        authorized.getProviderRef(),
                        authorized.getPspId(),
                        payment.getAmountMinor()));

        if (response.outcome() != ConnectorApi.Outcome.APPROVED) {
            // A refused capture is not a failed payment. The authorization still
            // stands and the money has not moved, so the payment stays
            // AUTHORIZED and somebody can try again or let the hold expire.
            // Transitioning to FAILED here would discard a live authorization on
            // the strength of one refusal.
            log.warn("provider refused the capture - the authorization still stands",
                    LogEvent.event()
                            .with(LogFields.PAYMENT_ID, paymentId.toString())
                            .with(LogFields.PSP_ID, authorized.getPspId())
                            .with(LogFields.OPERATION, "capture")
                            .with(LogFields.OUTCOME, "DECLINED")
                            .with(LogFields.ERROR_CODE, response.errorCode())
                            .args());
            throw new ApiException(HttpStatus.CONFLICT, "capture_declined",
                    "the provider refused the capture: " + response.errorCode());
        }

        Payment captured = persistence.recordCaptured(paymentId);
        log.info("payment captured",
                LogEvent.event()
                        .with(LogFields.PAYMENT_ID, paymentId.toString())
                        .with(LogFields.PSP_ID, authorized.getPspId())
                        .with(LogFields.OPERATION, "capture")
                        .with(LogFields.AMOUNT_MINOR, payment.getAmountMinor())
                        .with(LogFields.PREVIOUS_STATE, PaymentState.AUTHORIZED.name())
                        .with(LogFields.STATE, PaymentState.CAPTURED.name())
                        .args());
        return toResponse(captured);
    }

    /**
     * Authorizes the payment, failing over to another provider when - and only
     * when - the previous one provably never received the request.
     *
     * <p>The loop is the whole of phase 5's failover. Everything that makes it
     * safe is in {@link FailoverPolicy} and in {@code PaymentTransitions}; what
     * is here is the bookkeeping: which providers have been tried, and when to
     * stop.
     */
    /**
     * Authorizes the payment, failing over to another provider when - and only
     * when - the previous one provably never received the request.
     *
     * <p>The loop is all of phase 5's failover. Everything that makes it safe
     * lives elsewhere and deliberately so: {@link FailoverPolicy} decides
     * whether an error proves nothing was sent, and {@code PaymentTransitions}
     * enforces the same rule a second time by refusing to route a payment that
     * has already reached {@code UNKNOWN}. What is here is only the
     * bookkeeping - which providers have been tried, and when to stop.
     */
    private OrchestratorApi.PaymentResponse authorize(UUID paymentId) {
        Set<String> tried = new LinkedHashSet<>();

        while (true) {
            Attempt outcome = attemptOnce(paymentId, tried);
            if (outcome.response() != null) {
                return outcome.response();
            }
            tried.add(outcome.pspId());

            if (tried.size() >= FailoverPolicy.MAX_PROVIDERS_PER_PAYMENT) {
                return giveUp(paymentId, outcome, "failover_exhausted");
            }

            log.warn("failing over to another provider",
                    LogEvent.event()
                            .with(LogFields.PAYMENT_ID, paymentId.toString())
                            .with(LogFields.PSP_ID, outcome.pspId())
                            .with(LogFields.ERROR_CODE, outcome.errorCode())
                            .with(LogFields.OUTCOME, "FAILOVER")
                            .args());
        }
    }

    /**
     * Ends a payment that ran out of providers to try.
     *
     * <p>The payment is in {@code ROUTED} at this point - alive, with a failed
     * attempt behind it - so it is closed with the error code of the attempt
     * that failed last. The merchant sees the real reason rather than
     * "failover_exhausted", which describes our plumbing and not their payment.
     */
    private OrchestratorApi.PaymentResponse giveUp(UUID paymentId, Attempt last, String reason) {
        Payment failed = persistence.markUnroutable(paymentId);

        log.warn("no provider could accept the payment",
                LogEvent.event()
                        .with(LogFields.PAYMENT_ID, paymentId.toString())
                        .with(LogFields.PSP_ID, last.pspId())
                        .with(LogFields.STATE, failed.getState().name())
                        .with(LogFields.OUTCOME, "FAILED")
                        .with(LogFields.ERROR_CODE, last.errorCode())
                        .with(LogFields.OPERATION, reason)
                        .args());
        return toResponse(failed);
    }

    /**
     * The result of offering a payment to one provider.
     *
     * <p>A non-null {@code response} means the payment reached a terminal state
     * and the caller is done. A null one means the attempt failed in a way that
     * permits another provider to be tried, which is the only circumstance in
     * which {@code errorCode} is set.
     */
    private record Attempt(OrchestratorApi.PaymentResponse response,
                           String pspId,
                           String errorCode) {

        static Attempt done(OrchestratorApi.PaymentResponse response) {
            return new Attempt(response, null, null);
        }

        static Attempt mayFailOver(String pspId, String errorCode) {
            return new Attempt(null, pspId, errorCode);
        }
    }

    private Attempt attemptOnce(UUID paymentId, Set<String> exclude) {
        Optional<PaymentAttempt> opened = persistence.beginAuthorization(paymentId, exclude);
        if (opened.isEmpty()) {
            Payment failed = persistence.markUnroutable(paymentId);
            log.warn("payment could not be routed",
                    LogEvent.event()
                            .with(LogFields.PAYMENT_ID, paymentId.toString())
                            .with(LogFields.STATE, failed.getState().name())
                            .with(LogFields.ERROR_CODE, "no_route")
                            .args());
            return Attempt.done(toResponse(failed));
        }

        PaymentAttempt attempt = opened.get();
        Payment routed = persistence.find(paymentId).orElseThrow();

        // The attempt id becomes the provider's idempotency reference. Using a
        // stable, already-persisted identifier - rather than a fresh one per
        // call - is what makes a retry in phase 3 safe to add.
        ConnectorApi.AuthorizeRequest request = new ConnectorApi.AuthorizeRequest(
                attempt.getId().toString(),
                attempt.getPspId(),
                routed.getAmountMinor(),
                routed.getCurrency(),
                routed.getCardToken(),
                routed.getCardBin(),
                routed.getCardLast4());

        long startedAt = System.nanoTime();
        Payment result;
        try {
            ConnectorApi.AuthorizeResponse response = connector.authorize(request);
            long latencyMs = elapsedMs(startedAt);

            result = response.outcome() == ConnectorApi.Outcome.APPROVED
                    ? persistence.recordApproved(paymentId, attempt.getId(), response.providerRef(), latencyMs)
                    : persistence.recordDeclined(paymentId, attempt.getId(),
                            response.providerRef(), response.errorCode(), latencyMs);

        } catch (ConnectorClient.ConnectorUnavailableException e) {
            // The transition that the whole design exists for. No answer came
            // back, so the payment is UNKNOWN - not FAILED. The provider may
            // have authorized the card and lost the response on the way home,
            // and calling that a failure is how a caller is invited to retry
            // into a double charge.
            // NEVER a failover. The request went out and no answer came back,
            // so the card may already be charged; offering it to a second
            // provider is the double charge phase 5's nuance section is about.
            // PaymentTransitions enforces this independently - UNKNOWN has no
            // path back to ROUTED - so this is belt and braces, on purpose.
            return Attempt.done(recordUnresolved(paymentId, attempt, "connector_unavailable", startedAt));

        } catch (ConnectorClient.ConnectorRejectedException e) {
            // 3c. The connector's breaker is open, so nothing was sent. The card
            // was definitely not charged - FAILED, and safely retryable by the
            // merchant. Recording UNKNOWN here would manufacture the very
            // uncertainty the breaker exists to prevent, and hand phase 8's
            // poller a reference that was never issued.
            // Nothing was sent, so another provider may have this payment.
            // recordFailedButRoutable keeps the payment alive in ROUTED rather
            // than closing it, which recordDeclined would do irreversibly.
            persistence.recordFailedButRoutable(
                    paymentId, attempt.getId(), "circuit_open", elapsedMs(startedAt));

            log.warn("authorization refused: provider circuit open",
                    LogEvent.event()
                            .with(LogFields.PAYMENT_ID, paymentId.toString())
                            .with(LogFields.ATTEMPT_NO, attempt.getAttemptNo())
                            .with(LogFields.PSP_ID, attempt.getPspId())
                            .with(LogFields.OUTCOME, "NOT_SENT")
                            .with(LogFields.ERROR_CODE, "circuit_open")
                            .args());
            return Attempt.mayFailOver(attempt.getPspId(), "circuit_open");

        } catch (DeadlineExceededException e) {
            // 3a. The budget ran out - and which of the two ways it ran out
            // decides the payment's state, which is why DeadlineExceededException
            // carries the flag rather than being one undifferentiated "timeout".
            if (e.wasStarted()) {
                // The request went out and was abandoned mid-flight. The
                // provider may already have authorized the card.
                // Also never a failover: the request was in flight when it was
                // abandoned, so the provider may have completed it.
                return Attempt.done(recordUnresolved(paymentId, attempt, "deadline_abandoned", startedAt));
            }
            // There was too little budget left to send anything at all, so the
            // card was demonstrably not charged. That makes this FAILED, and a
            // FAILED payment is one a merchant may safely retry - which is the
            // entire practical value of drawing the distinction.
            persistence.recordFailedButRoutable(
                    paymentId, attempt.getId(), "deadline_exceeded", elapsedMs(startedAt));

            log.warn("authorization not attempted: out of budget",
                    LogEvent.event()
                            .with(LogFields.PAYMENT_ID, paymentId.toString())
                            .with(LogFields.ATTEMPT_NO, attempt.getAttemptNo())
                            .with(LogFields.PSP_ID, attempt.getPspId())
                            .with(LogFields.OUTCOME, "NOT_SENT")
                            .with(LogFields.ERROR_CODE, "deadline_exceeded")
                            .with(LogFields.DEADLINE_REMAINING_MS, e.remainingMs())
                            .args());
            // Eligible in principle; in practice the next attempt will usually
            // find the budget just as empty and end the payment on the spot.
            // That is the deadline bounding failover independently of the
            // provider count, which is the behaviour we want.
            return Attempt.mayFailOver(attempt.getPspId(), "deadline_exceeded");
        }

        log.info("authorization completed",
                LogEvent.event()
                        .with(LogFields.PAYMENT_ID, paymentId.toString())
                        .with(LogFields.ATTEMPT_NO, attempt.getAttemptNo())
                        .with(LogFields.PSP_ID, attempt.getPspId())
                        .with(LogFields.PREVIOUS_STATE, "AUTHORIZING")
                        .with(LogFields.STATE, result.getState().name())
                        .with(LogFields.LATENCY_MS, elapsedMs(startedAt))
                        .args());

        // Phase 6. The payment has reached a terminal state, so the rest of the
        // system is told. In the direct arm this publishes inline and can lose
        // the event; in the outbox arm it is a row in the same transaction.
        events.emit(result);

        // Approved, or DECLINED. A decline is a definite answer from a working
        // provider and is never failed over: retrying a refusal on a second
        // provider is how one decline becomes a card-issuer fraud flag.
        return Attempt.done(toResponse(result));
    }

    /**
     * The payment may or may not have been charged. Records {@code UNKNOWN} and
     * says so.
     *
     * <p>Shared by the two paths that reach it - no answer from the connector,
     * and a call abandoned when the budget ran out - because they are the same
     * fact about the world, and letting them drift apart is how one of them
     * eventually gets recorded as {@code FAILED} by accident.
     */
    private OrchestratorApi.PaymentResponse recordUnresolved(UUID paymentId,
                                                             PaymentAttempt attempt,
                                                             String errorCode,
                                                             long startedAt) {
        Payment result = persistence.recordUnknown(
                paymentId, attempt.getId(), errorCode, elapsedMs(startedAt));
        events.emit(result);

        log.warn("authorization outcome unknown",
                LogEvent.event()
                        .with(LogFields.PAYMENT_ID, paymentId.toString())
                        .with(LogFields.ATTEMPT_NO, attempt.getAttemptNo())
                        .with(LogFields.PSP_ID, attempt.getPspId())
                        .with(LogFields.STATE, result.getState().name())
                        .with(LogFields.OUTCOME, "UNKNOWN")
                        .with(LogFields.ERROR_CODE, errorCode)
                        .args());
        return toResponse(result);
    }

    private OrchestratorApi.PaymentResponse toResponse(Payment payment) {
        PaymentAttempt latest = persistence.latestAttempt(payment.getId()).orElse(null);

        return new OrchestratorApi.PaymentResponse(
                payment.getId().toString(),
                payment.getMerchantId().toString(),
                payment.getState().name(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                payment.getCardBin(),
                payment.getCardLast4(),
                payment.getPspId(),
                latest == null ? null : latest.getProviderRef(),
                latest == null ? null : latest.getErrorCode(),
                payment.getMerchantReference(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
