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

import com.payorch.connector.provider.PspAdapter;
import com.payorch.connector.provider.StatusFanout;

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
    private final CaptureService captures;
    private final ReversalService reversals;

    /** Phase 8a. Built in 7f with no caller; this is it. */
    private final StatusFanout fanout;

    public ConnectorController(AuthorizationService authorizations, CaptureService captures,
                               ReversalService reversals, StatusFanout fanout) {
        this.authorizations = authorizations;
        this.captures = captures;
        this.reversals = reversals;
        this.fanout = fanout;
    }

    @PostMapping("/authorize")
    public ConnectorApi.AuthorizeResponse authorize(
            @Valid @RequestBody ConnectorApi.AuthorizeRequest request) {
        return authorizations.authorize(request);
    }

    /**
     * Phase 6j. Every exception handler below applies to this unchanged, and the
     * meanings carry across intact: 503 still says nothing was sent, 502 still
     * says the outcome is unknown. For a capture that second one is heavier -
     * "unknown" here means the customer may have been charged - but the
     * distinction is the same one, which is exactly why it was worth making
     * precisely in phase 1.
     */
    @PostMapping("/capture")
    public ConnectorApi.CaptureResponse capture(
            @Valid @RequestBody ConnectorApi.CaptureRequest request) {
        return captures.capture(request);
    }

    /**
     * Phase 6k. The saga's compensating action, and the handlers below matter
     * more here than anywhere else on this controller.
     *
     * <p>503 means nothing was sent, so the compensation has simply not happened
     * yet and the caller's ladder should hold it and try again - which is the
     * correct behaviour against a provider that is struggling, and is why the
     * reversal goes through the breaker rather than around it.
     *
     * <p>502 means the outcome is unknown, and for a reversal that is the worst
     * answer in this system: a compensation whose result nobody knows, for a
     * capture whose result nobody knows. Retrying it is safe - the provider
     * recognises a reversal it has already done - but retrying cannot resolve
     * it. Only asking the provider what it did resolves it, and that is phase 8.
     */
    /**
     * Phase 8a. Asks every provider about one reference, in parallel.
     *
     * <p>This is {@code StatusFanout}'s caller - it was built in 7f with none,
     * because the thing that needs it is the {@code UNKNOWN} poller and that
     * belongs to this phase.
     *
     * <h2>Always 200, even when nobody has it</h2>
     *
     * <p>"No provider holds this reference" is a successful answer to the
     * question that was asked, not a 404. A 404 would say "no such lookup
     * endpoint" to anything reading status codes generically - a retry layer, a
     * dashboard, the orchestrator's own client - and it would make the most
     * important answer this endpoint can give indistinguishable from a routing
     * mistake.
     *
     * <p>The caller is expected to read {@code silent} before acting. See
     * {@code LookupResponse.definitivelyAbsent}.
     */
    @PostMapping("/lookup")
    public ConnectorApi.LookupResponse lookup(@Valid @RequestBody ConnectorApi.LookupRequest request) {
        StatusFanout.FanoutResult result = fanout.askEveryone(request.reference());

        PspAdapter.ProviderLookup claimed = result.claimed().orElse(null);
        return new ConnectorApi.LookupResponse(
                request.reference(),
                claimed == null ? null : claimed.pspId(),
                claimed == null ? null : outcomeOf(claimed),
                claimed != null && claimed.captured(),
                claimed != null && claimed.reversed(),
                claimed == null ? 0 : claimed.amountMinor(),
                result.answers().stream().map(PspAdapter.ProviderLookup::pspId).toList(),
                result.silent());
    }

    /**
     * The provider's own word for what it did, mapped onto our two outcomes.
     *
     * <p>Anything that is not an explicit approval is a decline. A provider that
     * holds the reference and will not call it approved has not approved it, and
     * guessing in the generous direction here would resolve an UNKNOWN payment
     * to AUTHORIZED on the strength of a string nobody recognised.
     */
    private static ConnectorApi.Outcome outcomeOf(PspAdapter.ProviderLookup lookup) {
        return "APPROVED".equals(lookup.outcome())
                ? ConnectorApi.Outcome.APPROVED
                : ConnectorApi.Outcome.DECLINED;
    }

    @PostMapping("/reverse")
    public ConnectorApi.ReverseResponse reverse(
            @Valid @RequestBody ConnectorApi.ReverseRequest request) {
        return reversals.reverse(request);
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
                        .with(LogFields.OUTCOME, "UNKNOWN")
                        .with(LogFields.ERROR_CODE, "provider_unavailable")
                        .args());
        return problem;
    }
}
