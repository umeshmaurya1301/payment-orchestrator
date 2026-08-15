package com.payorch.orchestrator;

import java.util.Optional;
import java.util.UUID;

import com.payorch.infra.logging.LogEvent;
import com.payorch.infra.logging.LogFields;
import com.payorch.infra.persistence.Uuid7;
import com.payorch.infra.web.ApiException;
import com.payorch.orchestrator.api.OrchestratorApi;
import com.payorch.orchestrator.connector.ConnectorApi;
import com.payorch.orchestrator.connector.ConnectorClient;
import com.payorch.orchestrator.domain.Payment;
import com.payorch.orchestrator.domain.PaymentAttempt;
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
 * <p>The flow is synchronous and has no resilience of any kind: no timeout, no
 * retry, no breaker, no fallback. Phase 2 breaks exactly this path and measures
 * what happens; phase 3 adds one defence at a time against those measurements.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentPersistence persistence;
    private final ConnectorClient connector;

    public PaymentService(PaymentPersistence persistence, ConnectorClient connector) {
        this.persistence = persistence;
        this.connector = connector;
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

    private OrchestratorApi.PaymentResponse authorize(UUID paymentId) {
        Optional<PaymentAttempt> opened = persistence.beginAuthorization(paymentId);
        if (opened.isEmpty()) {
            Payment failed = persistence.markUnroutable(paymentId);
            log.warn("payment could not be routed",
                    LogEvent.event()
                            .with(LogFields.PAYMENT_ID, paymentId.toString())
                            .with(LogFields.STATE, failed.getState().name())
                            .with(LogFields.ERROR_CODE, "no_route")
                            .args());
            return toResponse(failed);
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
            result = persistence.recordUnknown(
                    paymentId, attempt.getId(), "connector_unavailable", elapsedMs(startedAt));

            log.warn("authorization outcome unknown",
                    LogEvent.event()
                            .with(LogFields.PAYMENT_ID, paymentId.toString())
                            .with(LogFields.ATTEMPT_NO, attempt.getAttemptNo())
                            .with(LogFields.PSP_ID, attempt.getPspId())
                            .with(LogFields.STATE, result.getState().name())
                            .with(LogFields.OUTCOME, "UNKNOWN")
                            .with(LogFields.ERROR_CODE, "connector_unavailable")
                            .args());
            return toResponse(result);
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
