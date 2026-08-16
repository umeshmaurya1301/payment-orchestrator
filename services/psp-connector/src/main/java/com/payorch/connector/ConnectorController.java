package com.payorch.connector;

import com.payorch.connector.api.ConnectorApi;
import com.payorch.connector.provider.PspAdapter;
import com.payorch.infra.resilience.bulkhead.BulkheadFullException;
import com.payorch.infra.resilience.ratelimit.RateLimitedException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import com.payorch.infra.logging.LogEvent;
import com.payorch.infra.logging.LogFields;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The connector's only endpoint in phase 1.
 *
 * <p>{@code /internal} because this is service-to-service, not merchant-facing:
 * there is no API key here and no rate limit, and the path prefix is what makes
 * that obvious to anyone reading an access log or a network policy.
 */
@RestController
@RequestMapping("/internal/v1")
public class ConnectorController {

    private static final Logger log = LoggerFactory.getLogger(ConnectorController.class);

    private final AuthorizationService authorizations;

    public ConnectorController(AuthorizationService authorizations) {
        this.authorizations = authorizations;
    }

    @PostMapping("/authorize")
    public ConnectorApi.AuthorizeResponse authorize(
            @Valid @RequestBody ConnectorApi.AuthorizeRequest request) {
        return authorizations.authorize(request);
    }

    /**
     * The bulkhead shed this call, so <strong>nothing was sent</strong>.
     *
     * <p>503, the same contract as an open breaker, because it is the same fact:
     * the provider was not contacted and the card was not charged. Load shedding
     * that produced {@code UNKNOWN} payments would be self-defeating - the system
     * would protect its heap by manufacturing liabilities in its ledger.
     */
    @ExceptionHandler(BulkheadFullException.class)
    public ProblemDetail handleBulkheadFull(BulkheadFullException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle("Provider at capacity");
        problem.setDetail("The concurrency limit for this provider was reached. "
                + "The request was not sent and the card was not charged.");
        problem.setProperty(LogFields.ERROR_CODE, "bulkhead_full");
        log.debug("bulkhead full for {}", ex.key());
        return problem;
    }

    /**
     * The egress limiter refused: sending would breach the provider's contracted
     * rate. <strong>Nothing was sent.</strong>
     *
     * <p>503 again, and the third exception mapped to it, which is the point
     * rather than a smell: an open breaker, a full bulkhead and an exhausted rate
     * budget are three different reasons for one fact the orchestrator cares
     * about - the provider was not contacted, so the payment is definitely
     * {@code FAILED} and not {@code UNKNOWN}. The distinction between them lives
     * in {@code errorCode}, where an operator can act on it, rather than in the
     * status, where the caller would have to.
     *
     * <p>{@code Retry-After} is carried through because unlike the other two this
     * one knows exactly when capacity returns - it is our own arithmetic.
     */
    @ExceptionHandler(RateLimitedException.class)
    public ProblemDetail handleEgressRateLimited(RateLimitedException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle("Provider rate limit reached");
        problem.setDetail("Sending this request would exceed the rate agreed with the provider. "
                + "The request was not sent and the card was not charged.");
        problem.setProperty(LogFields.ERROR_CODE, "provider_rate_limited");
        problem.setProperty("retryAfterMs", ex.retryAfterMs());
        log.debug("egress rate limit reached for {}", ex.key());
        return problem;
    }

    /**
     * The circuit breaker is open, so <strong>nothing was sent</strong>.
     *
     * <p>503, deliberately distinct from the 502 below. The two look similar and
     * mean opposite things to a payment: 502 says the provider may have acted
     * and the outcome is unknown; 503 says the request never left this service
     * and the card was definitely not charged. Collapsing them would turn every
     * fast rejection into an {@code UNKNOWN} payment needing a status poll -
     * manufacturing exactly the uncertainty the breaker exists to avoid.
     */
    @ExceptionHandler(CallNotPermittedException.class)
    public ProblemDetail handleCircuitOpen(CallNotPermittedException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle("Provider circuit open");
        problem.setDetail("The circuit breaker for this provider is open. "
                + "The request was not sent and the card was not charged.");
        problem.setProperty(LogFields.ERROR_CODE, "circuit_open");

        // DEBUG, not WARN. An open breaker rejects every call for its whole open
        // window - at load that is thousands of lines saying the same thing. The
        // state TRANSITION is logged once, at WARN, by CircuitBreakers.
        log.debug("circuit open, call not permitted");
        return problem;
    }

    /**
     * Translates "the provider did not answer" into 502.
     *
     * <p>The status carries meaning the orchestrator acts on. A 502 says the
     * outcome is unknown, and the payment moves to {@code UNKNOWN}. Letting this
     * fall through to the generic 500 handler would say the same thing by
     * accident, and the next person to add an exception type here would not know
     * which meaning was intended.
     */
    @ExceptionHandler(PspAdapter.ProviderUnavailableException.class)
    public ProblemDetail handleProviderUnavailable(PspAdapter.ProviderUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        problem.setTitle("Provider unavailable");
        problem.setDetail("The provider did not return a usable response. "
                + "The outcome of this authorization is unknown.");
        problem.setProperty(LogFields.ERROR_CODE, "provider_unavailable");

        // WARN with no stack trace. During a chaos run this fires thousands of
        // times, and stack traces would bury the lines that matter.
        log.warn("provider unavailable",
                LogEvent.event()
                        .with(LogFields.OPERATION, "authorize")
                        .with(LogFields.OUTCOME, "UNKNOWN")
                        .with(LogFields.ERROR_CODE, "provider_unavailable")
                        .args());
        return problem;
    }
}
