package com.payorch.mockpsp.api;

import com.payorch.infra.logging.LogEvent;
import com.payorch.infra.logging.LogFields;
import com.payorch.infra.web.ApiException;
import com.payorch.mockpsp.chaos.ChaosInjector;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The simulated acquirer.
 *
 * <p>Deterministic where it can be and random only where chaos says so. The
 * determinism matters: a test that wants a decline should be able to ask for one
 * without turning on {@code errorRate} and hoping.
 */
@RestController
@RequestMapping("/psp/v1")
public class ProviderController {

    private static final Logger log = LoggerFactory.getLogger(ProviderController.class);

    /**
     * Amounts whose last two minor digits are {@code 05} are declined.
     *
     * <p>A deterministic decline trigger, in the style real sandboxes use. It
     * separates "the provider said no", which is a normal business outcome, from
     * "the provider failed", which is what {@code errorRate} injects. Conflating
     * those two is the most common way a payment system ends up retrying a
     * decline.
     */
    private static final long DECLINE_TRIGGER = 5;

    private final AuthorizationLedger ledger;
    private final ChaosInjector chaos;

    public ProviderController(AuthorizationLedger ledger, ChaosInjector chaos) {
        this.ledger = ledger;
        this.chaos = chaos;
    }

    @PostMapping("/authorize")
    public ProviderApi.AuthorizeResponse authorize(@Valid @RequestBody ProviderApi.AuthorizeRequest request) {
        chaos.beforeResponse();

        // Provider-side idempotency, which is what makes retrying an authorize
        // safe. Phase 5's warning - that retrying on a DIFFERENT provider is
        // dangerous - is precisely because this guarantee does not span
        // providers.
        var existing = ledger.findFirstByReference(request.reference());
        if (existing.isPresent() && !chaos.shouldDuplicate()) {
            return toAuthorizeResponse(existing.get());
        }
        if (existing.isPresent()) {
            // The injected fault, stated plainly in the log so an experiment
            // write-up can point at the line. This is a second live
            // authorization for one reference: a double charge.
            log.warn("duplicate authorization injected",
                    LogEvent.event()
                            .with(LogFields.OPERATION, "authorize")
                            .with(LogFields.OUTCOME, "duplicate")
                            .args());
        }

        boolean declined = request.amountMinor() % 100 == DECLINE_TRIGGER;
        String last4 = lastFour(request.pan());

        var authorization = ledger.record(
                request.reference(),
                declined ? ProviderApi.Outcome.DECLINED : ProviderApi.Outcome.APPROVED,
                declined ? "insufficient_funds" : null,
                request.amountMinor(),
                request.currency(),
                last4);

        log.info("authorization processed",
                LogEvent.event()
                        .with(LogFields.OPERATION, "authorize")
                        .with(LogFields.OUTCOME, authorization.outcome().name())
                        .with(LogFields.LAST4, last4)
                        .with(LogFields.AMOUNT_MINOR, request.amountMinor())
                        .with(LogFields.CURRENCY, request.currency())
                        .args());

        return toAuthorizeResponse(authorization);
    }

    @PostMapping("/capture")
    public ProviderApi.CaptureResponse capture(@Valid @RequestBody ProviderApi.CaptureRequest request) {
        chaos.beforeResponse();

        var authorization = ledger.findByProviderRef(request.providerRef())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "unknown_provider_ref",
                        "no authorization with that provider reference"));

        if (authorization.outcome() != ProviderApi.Outcome.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "not_authorized",
                    "the authorization was not approved and cannot be captured");
        }
        if (request.amountMinor() > authorization.amountMinor()) {
            throw new ApiException(HttpStatus.CONFLICT, "capture_exceeds_authorization",
                    "capture amount exceeds the authorized amount");
        }

        var captured = ledger.capture(authorization, request.amountMinor());
        return new ProviderApi.CaptureResponse(
                captured.providerRef(), captured.outcome(), null, captured.capturedAmountMinor());
    }

    /**
     * Gives a capture back. Phase 6k, and the only compensating action the
     * provider offers.
     *
     * <h2>Idempotent, and it has to be more carefully than capture is</h2>
     *
     * <p>Capturing twice is caught by the state check above - the second call
     * finds an authorization that is already captured and simply replaces the
     * capture with an identical one. A reversal is money going the other way, so
     * a second call that "just did it again" would give the money back twice.
     * Reversing an already-reversed authorization therefore returns the SAME
     * answer without moving anything, which is what makes it safe for the saga
     * to retry - and the saga will retry, because a compensation that cannot be
     * retried is a compensation that fails permanently the first time the
     * network hiccups.
     *
     * <h2>409 on an uncaptured authorization</h2>
     *
     * <p>Not a silent success. Reversing something that was never captured is
     * the caller believing money moved when it did not, and answering 200 would
     * confirm that belief. The saga's whole purpose is to act on a disagreement
     * about whether money moved, so the one thing the provider must not do is
     * agree politely.
     */
    @PostMapping("/reverse")
    public ProviderApi.ReverseResponse reverse(@Valid @RequestBody ProviderApi.ReverseRequest request) {
        chaos.beforeResponse();

        var authorization = ledger.findByProviderRef(request.providerRef())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "unknown_provider_ref",
                        "no authorization with that provider reference"));

        if (!authorization.captured()) {
            throw new ApiException(HttpStatus.CONFLICT, "not_captured",
                    "the authorization was never captured, so there is nothing to reverse");
        }

        if (authorization.reversed()) {
            log.info("reversal ignored - already reversed",
                    LogEvent.event()
                            .with(LogFields.OPERATION, "reverse")
                            .with(LogFields.OUTCOME, "DUPLICATE")
                            .with(LogFields.AMOUNT_MINOR, authorization.capturedAmountMinor())
                            .args());
            return new ProviderApi.ReverseResponse(authorization.providerRef(),
                    ProviderApi.Outcome.APPROVED, null, authorization.capturedAmountMinor());
        }

        var reversed = ledger.reverse(authorization);
        log.info("capture reversed",
                LogEvent.event()
                        .with(LogFields.OPERATION, "reverse")
                        .with(LogFields.OUTCOME, "APPROVED")
                        .with(LogFields.AMOUNT_MINOR, reversed.capturedAmountMinor())
                        .with(LogFields.CURRENCY, reversed.currency())
                        .args());

        return new ProviderApi.ReverseResponse(reversed.providerRef(),
                ProviderApi.Outcome.APPROVED, null, reversed.capturedAmountMinor());
    }

    @GetMapping("/authorizations/{providerRef}")
    public ProviderApi.StatusResponse status(@PathVariable String providerRef) {
        chaos.beforeResponse();

        return ledger.findByProviderRef(providerRef)
                .map(ProviderController::toStatus)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "unknown_provider_ref",
                        "no authorization with that provider reference"));
    }

    /**
     * Every authorization the provider holds for one caller reference.
     *
     * <p>This is how phase 8's status poller resolves an {@code UNKNOWN} payment:
     * the caller never learned a provider reference, so it can only ask "what did
     * you do with my reference". It is also how a duplicate becomes visible -
     * two entries here for one reference is a double charge, and no
     * providerRef-keyed lookup can show that.
     */
    @GetMapping("/references/{reference}")
    public ProviderApi.ReferenceView byReference(@PathVariable String reference) {
        chaos.beforeResponse();

        return new ProviderApi.ReferenceView(
                reference,
                ledger.findAllByReference(reference).stream().map(ProviderController::toStatus).toList());
    }

    private static ProviderApi.AuthorizeResponse toAuthorizeResponse(AuthorizationLedger.Authorization a) {
        return new ProviderApi.AuthorizeResponse(
                a.providerRef(), a.reference(), a.outcome(), a.errorCode(),
                a.authCode(), a.amountMinor(), a.currency(), a.last4(), a.createdAt());
    }

    private static ProviderApi.StatusResponse toStatus(AuthorizationLedger.Authorization a) {
        return new ProviderApi.StatusResponse(
                a.providerRef(), a.reference(), a.outcome(), a.errorCode(),
                a.amountMinor(), a.currency(), a.last4(), a.captured(), a.reversed(),
                a.createdAt());
    }

    /**
     * The only thing the simulator keeps from the card number. The rest is read
     * from the request object and never written anywhere.
     */
    private static String lastFour(String pan) {
        String digits = com.payorch.infra.logging.Masking.digitsOf(pan);
        return digits.length() < 4 ? "0000" : digits.substring(digits.length() - 4);
    }
}
