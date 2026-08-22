package com.payorch.edge;

import java.util.UUID;

import com.payorch.edge.api.EdgeApi;
import com.payorch.edge.merchant.ApiKeyAuthFilter;
import com.payorch.edge.orchestrator.OrchestratorClient;
import org.infra.idempotency.IdempotencyGuard;
import org.infra.idempotency.IdempotencyKeys;
import org.infra.idempotency.RequestFingerprint;
import org.infra.idempotency.ReplayableResponse;
import org.infra.logging.LogEvent;
import org.infra.logging.LogFields;
import org.infra.logging.mask.Redactor;
import org.infra.resilience.deadline.DeadlineExceededException;
import org.infra.tokenization.TokenVault;
import org.infra.tokenization.TokenizedCard;
import org.infra.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * The merchant-facing API.
 *
 * <p>Two things happen here that happen nowhere else: a raw card number is
 * turned into a token, and a response is rendered to bytes so it can be replayed
 * verbatim. Both are explained below, and both are the reason this controller
 * returns {@code byte[]} instead of a DTO.
 */
@RestController
@RequestMapping("/v1/payments")
public class PaymentsController {

    private static final Logger log = LoggerFactory.getLogger(PaymentsController.class);

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final TokenVault vault;
    private final IdempotencyGuard idempotency;
    private final RequestFingerprint fingerprints;
    private final OrchestratorClient orchestrator;
    private final ObjectMapper json;

    public PaymentsController(TokenVault vault,
                              IdempotencyGuard idempotency,
                              RequestFingerprint fingerprints,
                              OrchestratorClient orchestrator,
                              ObjectMapper json) {
        this.vault = vault;
        this.idempotency = idempotency;
        this.fingerprints = fingerprints;
        this.orchestrator = orchestrator;
        this.json = json;
    }

    /**
     * Creates a payment.
     *
     * <p>Returns {@code byte[]} rather than {@link EdgeApi.PaymentResponse}, and
     * that is the design rather than a shortcut. The bytes written on the first
     * request are the bytes stored, and the bytes stored are what every later
     * request with the same key receives. Returning a DTO would mean the replay
     * path re-serializes a stored object and merely hopes the output matches -
     * it will not, the first time field ordering shifts or a timestamp formats
     * differently, and nothing would notice.
     */
    @PostMapping
    public ResponseEntity<byte[]> create(
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody EdgeApi.CreatePaymentRequest request,
            HttpServletRequest httpRequest) {

        if (!IdempotencyKeys.isValid(idempotencyKey)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_idempotency_key",
                    "An Idempotency-Key header of 1-" + IdempotencyKeys.MAX_LENGTH
                            + " printable non-whitespace characters is required.");
        }
        UUID merchantId = authenticatedMerchant(httpRequest);

        ReplayableResponse response = idempotency.execute(
                merchantId, idempotencyKey, fingerprintOf(request),
                () -> render(HttpStatus.CREATED, process(merchantId, idempotencyKey, request)));

        return toEntity(response);
    }

    /**
     * What this request asked for, as one comparable value. Phase 7a.
     *
     * <h2>The field list is the decision</h2>
     *
     * <p>Everything that changes what the payment DOES is in it, and nothing
     * else is. Amount, currency and the full card are obvious - a key reused
     * across two of any of those is two different charges. The two judgement
     * calls:
     *
     * <ul>
     *   <li><strong>{@code merchantReference} is included.</strong> It does not
     *       change where the money goes, so it is arguably cosmetic. It is also
     *       the merchant's own handle on the payment, and a key reused across
     *       two different references is a client that has lost track of which
     *       order it is paying for - which is precisely the bug worth
     *       surfacing.</li>
     *   <li><strong>The CVV is NOT included.</strong> It never leaves
     *       {@code EdgeApi.Card} - not stored, not hashed, not forwarded - and
     *       putting it in the fingerprint material would make its digest a
     *       stored derivative of it, which is the thing phase 1 promised not to
     *       do. The cost is that a retry differing only in CVV replays instead
     *       of 422ing, and that is the right trade: a changed CVV with an
     *       identical card and amount is not a different payment.</li>
     * </ul>
     *
     * <p>The order is fixed and the encoding is unambiguous - see
     * {@link RequestFingerprint}. A concatenation would let an amount of 4200
     * with no reference collide with an amount of 42 and a reference of "00".
     */
    private String fingerprintOf(EdgeApi.CreatePaymentRequest request) {
        return fingerprints.of(
                Long.toString(request.amountMinor()),
                request.currency(),
                request.card().number(),
                Integer.toString(request.card().expiryMonth()),
                Integer.toString(request.card().expiryYear()),
                request.merchantReference());
    }

    @GetMapping("/{id}")
    public EdgeApi.PaymentResponse get(@PathVariable String id, HttpServletRequest httpRequest) {
        UUID merchantId = authenticatedMerchant(httpRequest);

        OrchestratorClient.PaymentResponse payment = orchestrator.find(id)
                .filter(found -> merchantId.toString().equals(found.merchantId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "payment_not_found",
                        "no payment with that id"));

        return toEdgeResponse(payment);
    }

    /**
     * Live payment status, as Server-Sent Events. Phase 9a item 4.
     *
     * <h2>The thing it replaces</h2>
     *
     * <p>A merchant waiting for a payment to leave {@code AUTHORIZING} — or to
     * come back from {@code UNKNOWN} — calls {@code GET /v1/payments/{id}} in a
     * loop. Every one of those costs an API-key lookup, a rate-limit token, a
     * deadline scope, two network hops and a database read, and all but the last
     * returns exactly what the previous one did. The polling interval sets both
     * the load and the worst-case delay, and they move in opposite directions.
     *
     * <h2>Why SSE rather than a WebSocket</h2>
     *
     * <p>This is one-directional, short-lived, and the client is somebody's
     * backend rather than a browser with a reconnect library. SSE is a
     * {@code GET} that keeps the response open — it survives proxies, needs no
     * upgrade handshake, and a merchant can consume it with {@code curl}. A
     * WebSocket would add a protocol upgrade and a framing layer in order to
     * carry messages in the one direction SSE already carries them.
     *
     * <h2>Both transports serve this, deliberately differently</h2>
     *
     * <p>{@code orchestrator.watch} is a gRPC server stream when the edge is on
     * gRPC, and a polling loop when it is on REST. The merchant sees the same
     * endpoint either way — a merchant-visible feature must not appear and
     * disappear with an internal deployment choice — and experiment 28 measures
     * what the difference costs.
     *
     * <h2>Bounded, like everything else on this path</h2>
     *
     * <p>The stream ends at a terminal state or when the budget runs out,
     * whichever comes first. An open SSE connection is a held connection and a
     * held thread; phase 3's whole argument is that unbounded is the failure
     * being removed, and a stream with no ending would reintroduce it in a shape
     * that looks like a feature.
     */
    @GetMapping(value = "/{id}/events", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter events(
            @PathVariable String id,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "30") int seconds,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "250") long intervalMs,
            HttpServletRequest httpRequest) {

        UUID merchantId = authenticatedMerchant(httpRequest);

        // Ownership is checked ONCE, before anything is streamed. Checking it
        // per frame would be re-reading the same immutable fact, and not
        // checking it at all would let a merchant watch somebody else's payment
        // change state - which leaks more than the 404 this avoids, because it
        // leaks over time.
        OrchestratorClient.PaymentResponse initial = orchestrator.find(id)
                .filter(found -> merchantId.toString().equals(found.merchantId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "payment_not_found",
                        "no payment with that id"));

        java.time.Duration budget = java.time.Duration.ofSeconds(Math.clamp(seconds, 1, 120));

        // The emitter's own timeout is the budget plus a margin. Equal values
        // race: Spring would abort the connection at the same instant the watch
        // completes normally, turning a clean ending into an AsyncRequestTimeout
        // in the log on every single stream.
        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(
                budget.toMillis() + 2_000);

        // A virtual thread, not the request thread. SSE hands back the emitter
        // immediately and writes to it afterwards; doing the watch inline would
        // block the controller and never return one.
        Thread.ofVirtual().name("watch-" + id).start(() -> {
            try {
                orchestrator.watch(id, budget, Math.clamp(intervalMs, 50, 5_000), payment -> {
                    try {
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                                .event()
                                .name(payment.state())
                                .data(toEdgeResponse(payment)));
                    } catch (java.io.IOException e) {
                        // The merchant closed the connection. Normal, and the
                        // only way to find out is to fail writing to it.
                        throw new java.io.UncheckedIOException(e);
                    }
                });
                emitter.complete();

            } catch (java.io.UncheckedIOException e) {
                emitter.complete();
            } catch (RuntimeException e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Phase 6j. Capture a payment the merchant already holds an authorization for.
     *
     * <p><strong>No idempotency key, and that is a deliberate asymmetry worth
     * defending.</strong> Creating a payment needs one because a retried POST
     * would create a SECOND payment - a new resource, a second charge. Capture
     * names an existing resource and moves it AUTHORIZED -&gt; CAPTURED, a
     * transition the state machine permits exactly once; the second call gets a
     * 409 from {@code PaymentTransitions} rather than a second capture. The
     * safety comes from the state machine rather than from a key the merchant
     * has to remember to send, which is the stronger place for it to live.
     *
     * <p>The merchant filter is the same one {@code get} applies: a merchant may
     * only capture their own payment, and a payment belonging to somebody else
     * is a 404 rather than a 403 - saying "not yours" confirms it exists.
     */
    @PostMapping("/{id}/capture")
    public EdgeApi.PaymentResponse capture(@PathVariable String id, HttpServletRequest httpRequest) {
        UUID merchantId = authenticatedMerchant(httpRequest);

        orchestrator.find(id)
                .filter(found -> merchantId.toString().equals(found.merchantId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "payment_not_found",
                        "no payment with that id"));

        return toEdgeResponse(orchestrator.capture(id));
    }

    /**
     * The orchestrator refused the capture and said why. Passed through with its
     * own status rather than flattened to a 502: "this payment cannot be
     * captured" and "we could not reach the orchestrator" are different facts
     * and a merchant will retry exactly one of them.
     */
    @ExceptionHandler(OrchestratorClient.CaptureRefusedException.class)
    public ProblemDetail handleCaptureRefused(OrchestratorClient.CaptureRefusedException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(ex.status());
        problem.setTitle("Capture refused");
        problem.setDetail("The payment could not be captured. It may already be captured, "
                + "may not be authorized, or the provider declined.");
        problem.setProperty(LogFields.ERROR_CODE, "capture_refused");
        return problem;
    }

    /**
     * The tokenization boundary.
     *
     * <p>The card is swapped for a token before anything else touches the
     * request - before the orchestrator is called, before any log line is
     * written about the payment, and before any row exists that could hold it.
     * The CVV is used for nothing and is never referenced again after
     * validation; it dies with the request object.
     */
    private EdgeApi.PaymentResponse process(UUID merchantId,
                                            String idempotencyKey,
                                            EdgeApi.CreatePaymentRequest request) {
        TokenizedCard card = tokenize(request.card(), merchantId);

        // The first log line about this payment, and it already carries no card
        // number - only the token and the two displayable fragments. Named
        // fields, never the request object: LogEvent's allowlist is the control,
        // but `log.info("req={}", request)` would sidestep the intent of it.
        log.info("payment request accepted",
                LogEvent.event()
                        .with(LogFields.MERCHANT_ID, merchantId.toString())
                        .with(LogFields.IDEMPOTENCY_KEY, idempotencyKey)
                        .with(LogFields.AMOUNT_MINOR, request.amountMinor())
                        .with(LogFields.CURRENCY, request.currency())
                        .with(LogFields.TOKEN, card.token())
                        .with(LogFields.BIN, card.bin())
                        .with(LogFields.LAST4, card.last4())
                        .args());

        OrchestratorClient.PaymentResponse created = orchestrator.create(
                new OrchestratorClient.CreatePaymentRequest(
                        merchantId.toString(),
                        request.amountMinor(),
                        request.currency(),
                        card.token(),
                        card.bin(),
                        card.last4(),
                        // Redacted before it goes anywhere, and this is not
                        // belt-and-braces - it closes a real leak the phase-4
                        // test found by injecting a card number into it.
                        //
                        // merchantReference is free text the MERCHANT chooses.
                        // A merchant that writes a card number into it puts that
                        // number into our `payment` table and, because phase 1
                        // stores rendered response bytes for byte-identical
                        // replay, into our idempotency cache as well - two
                        // durable copies of a PAN in a system whose entire
                        // design is that PANs live only in the vault. The
                        // tokenization boundary holds for the `card` field
                        // because we control it; this field we do not.
                        //
                        // Redacting at the edge rather than in the orchestrator
                        // is deliberate: the edge is where untrusted input stops
                        // being untrusted, and every hop after this one would
                        // otherwise have to remember.
                        Redactor.redact(request.merchantReference())));

        return toEdgeResponse(created);
    }

    /**
     * @param merchantId the key scope. Phase 9c.
     *
     * <p>The merchant is the erasure boundary because that is who a
     * right-to-erasure request arrives for, and the scope has to be chosen at
     * the moment the card is first encrypted - records wrapped under a shared
     * key cannot be separated afterwards without decrypting and re-encrypting
     * them. So this parameter is not plumbing: it is the decision that makes
     * erasure possible at all, made in the one place that knows whose card this
     * is.
     */
    private TokenizedCard tokenize(EdgeApi.Card card, UUID merchantId) {
        try {
            return vault.tokenize(card.number(), card.expiryMonth(), card.expiryYear(),
                    merchantId.toString());
        } catch (IllegalArgumentException e) {
            // The message from Pan.of is written not to contain the input, which
            // is what makes it safe to hand back to the caller.
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_card", e.getMessage());
        }
    }

    private ReplayableResponse render(HttpStatus status, EdgeApi.PaymentResponse body) {
        return new ReplayableResponse(
                status.value(), MediaType.APPLICATION_JSON_VALUE, json.writeValueAsBytes(body));
    }

    private static ResponseEntity<byte[]> toEntity(ReplayableResponse response) {
        return ResponseEntity.status(response.status())
                .header("Content-Type", response.contentType())
                .body(response.body());
    }

    private static EdgeApi.PaymentResponse toEdgeResponse(OrchestratorClient.PaymentResponse payment) {
        return new EdgeApi.PaymentResponse(
                payment.id(),
                payment.state(),
                payment.amountMinor(),
                payment.currency(),
                payment.cardBin(),
                payment.cardLast4(),
                payment.merchantReference(),
                payment.createdAt());
    }

    private static UUID authenticatedMerchant(HttpServletRequest request) {
        Object merchantId = request.getAttribute(ApiKeyAuthFilter.MERCHANT_ATTRIBUTE);
        if (merchantId instanceof UUID id) {
            return id;
        }
        // Unreachable while the filter guards /v1/**. Kept as a hard failure
        // rather than an assumption, because the failure mode of getting this
        // wrong is processing a payment with no merchant attached to it.
        throw new IllegalStateException("no authenticated merchant on the request");
    }

    /**
     * A duplicate that waited for the first request and gave up.
     *
     * <p><strong>Much rarer since 7b, and much more informative because of
     * it.</strong> A duplicate no longer bounces off a claim it could have
     * waited out - it waits for whatever is left of its deadline and replays the
     * winner's answer. Reaching this handler now means one of three things, and
     * all three are worth knowing:
     *
     * <ul>
     *   <li>the first request is genuinely slower than this one's remaining
     *       budget, so the caller is being told to retry with a fresh budget
     *       rather than being made to wait past their own deadline;</li>
     *   <li>this request arrived with almost none of its budget left, and
     *       declining immediately leaves the caller what little they had;</li>
     *   <li>the first request failed and released its claim while this one was
     *       waiting - nothing is running and nothing is stored, so retrying is
     *       exactly the right next move.</li>
     * </ul>
     *
     * <p>Still 409 and still no body, because there is genuinely nothing to
     * replay. What changed is that it is now an answer rather than a shrug.
     */
    /**
     * The key has been used before, for something else. Phase 7a.
     *
     * <p><strong>422 rather than a replay, and that is the whole point of the
     * fingerprint.</strong> Same key with a different body is a client bug -
     * and replaying is the most dangerous possible response to it, because the
     * caller receives a 201 for a payment they did not ask for and has no way
     * to tell. A 422 costs them an error; a replay costs them a wrong answer
     * they will act on.
     *
     * <p>422 rather than 409 because the request is well-formed and
     * syntactically valid; what is wrong is its content, relative to state the
     * server already holds. And rather than 400, because nothing about this
     * request in isolation is malformed - it is only wrong in the presence of
     * the earlier one.
     *
     * <p>The detail names no field values from either body. The bodies being
     * compared contain card numbers, and a diff would be the shortest route
     * from a PAN to whatever logs a merchant's client writes when it gets a 422.
     */
    @ExceptionHandler(IdempotencyGuard.FingerprintMismatchException.class)
    public ProblemDetail handleFingerprintMismatch(
            IdempotencyGuard.FingerprintMismatchException ex) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setTitle("Idempotency key reused");
        problem.setDetail("This Idempotency-Key was already used for a different request. "
                + "Use a new key, or resend the original request unchanged.");
        problem.setProperty(LogFields.ERROR_CODE, "idempotency_key_reused");
        return problem;
    }

    @ExceptionHandler(IdempotencyGuard.InFlightException.class)
    public ProblemDetail handleInFlight(IdempotencyGuard.InFlightException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Request in progress");
        problem.setDetail("A request with this Idempotency-Key is still being processed.");
        problem.setProperty(LogFields.ERROR_CODE, "idempotency_in_flight");
        return problem;
    }

    /**
     * The orchestrator did not answer.
     *
     * <p>502, and the wording matters: the payment may exist. Telling a merchant
     * their payment failed when the truth is that we do not know is how a
     * merchant is invited to retry into a double charge - the same mistake the
     * {@code UNKNOWN} state exists to prevent one layer down.
     */
    /**
     * The request ran out of budget.
     *
     * <p>504, and the wording depends on which way it ran out. If nothing was
     * ever sent the payment definitely does not exist and the merchant can
     * simply try again; if a call was abandoned in flight it may exist, and the
     * merchant must retry with the <em>same</em> Idempotency-Key or risk paying
     * twice. Telling a merchant "it failed" when the truth is "we stopped
     * waiting" is how a duplicate charge gets made by a well-behaved client.
     */
    @ExceptionHandler(DeadlineExceededException.class)
    public ProblemDetail handleDeadlineExceeded(DeadlineExceededException ex) {
        log.warn("request exceeded its deadline budget",
                LogEvent.event()
                        .with(LogFields.OUTCOME, ex.wasStarted() ? "UNKNOWN" : "FAILED")
                        .with(LogFields.ERROR_CODE, "deadline_exceeded")
                        .with(LogFields.DEADLINE_REMAINING_MS, ex.remainingMs())
                        .args());

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.GATEWAY_TIMEOUT);
        problem.setTitle("Deadline exceeded");
        problem.setDetail(ex.wasStarted()
                ? "The payment could not be confirmed within the time budget. It may or may not "
                        + "have been created. Retry with the same Idempotency-Key rather than a new one."
                : "The request ran out of time before the payment was created. No payment exists.");
        problem.setProperty(LogFields.ERROR_CODE, "deadline_exceeded");
        return problem;
    }

    @ExceptionHandler(OrchestratorClient.OrchestratorUnavailableException.class)
    public ProblemDetail handleOrchestratorUnavailable(
            OrchestratorClient.OrchestratorUnavailableException ex) {
        log.warn("orchestrator unavailable",
                LogEvent.event()
                        .with(LogFields.OUTCOME, "UNKNOWN")
                        .with(LogFields.ERROR_CODE, "orchestrator_unavailable")
                        .args());

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        problem.setTitle("Payment status unknown");
        problem.setDetail("The payment could not be confirmed. It may or may not have been created. "
                + "Retry with the same Idempotency-Key rather than a new one.");
        problem.setProperty(LogFields.ERROR_CODE, "orchestrator_unavailable");
        return problem;
    }
}
