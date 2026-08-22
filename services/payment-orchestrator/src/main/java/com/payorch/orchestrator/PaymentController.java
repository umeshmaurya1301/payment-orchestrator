package com.payorch.orchestrator;

import java.util.UUID;

import org.infra.persistence.Uuid7;
import org.infra.logging.LogFields;
import org.infra.web.ApiException;
import com.payorch.orchestrator.api.OrchestratorApi;
import com.payorch.orchestrator.domain.PaymentTransitions;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service only. {@code payments-edge} is the merchant-facing surface;
 * nothing here authenticates a merchant, which is precisely why the path says
 * {@code /internal}.
 */
@RestController
@RequestMapping("/internal/v1/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrchestratorApi.PaymentResponse create(
            @Valid @RequestBody OrchestratorApi.CreatePaymentRequest request) {
        return payments.create(request);
    }

    /**
     * Phase 6j. Capture is a POST to a sub-resource rather than a PATCH of
     * {@code state}, because it is an ACTION with a side effect at a third party,
     * not an edit of a field. A caller that could PATCH state to CAPTURED would
     * be able to tell this service money had moved when it had not.
     */
    @PostMapping("/{id}/capture")
    public OrchestratorApi.PaymentResponse capture(@PathVariable String id) {
        UUID paymentId = Uuid7.parseOrNull(id);
        if (paymentId == null) {
            throw notFound();
        }
        return payments.capture(paymentId);
    }

    @GetMapping("/{id}")
    public OrchestratorApi.PaymentResponse get(@PathVariable String id) {
        UUID paymentId = Uuid7.parseOrNull(id);
        if (paymentId == null) {
            // A malformed id is a 404, not a 400 and certainly not a 500. There
            // is no payment at that address, and saying so leaks nothing about
            // which ids happen to be well-formed.
            throw notFound();
        }
        return payments.find(paymentId).orElseThrow(PaymentController::notFound);
    }

    /**
     * An illegal transition is a bug in this service, not a bad request.
     *
     * <p>Mapped explicitly so it answers 500 with a stack trace in the log,
     * rather than being quietly rendered as something the caller could fix.
     * Payments do not move along edges that are not in the table, and if one
     * appears to have, the correct response is a loud failure and an
     * investigation.
     */
    @ExceptionHandler(PaymentTransitions.IllegalTransitionException.class)
    public ProblemDetail handleIllegalTransition(PaymentTransitions.IllegalTransitionException ex) {
        log.error("illegal payment state transition", ex);

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal Server Error");
        problem.setDetail("The request could not be completed.");
        return problem;
    }

    /**
     * Two requests changed one payment at the same time. Phase 7d.
     *
     * <h2>Why this had never been handled, and what it was doing instead</h2>
     *
     * <p>{@code @Version} has been on {@link com.payorch.orchestrator.domain.Payment}
     * since phase 1 - added early on the argument that backfilling it later
     * would be worse - and until now nothing caught what it throws. A concurrent
     * update therefore produced an unhandled exception and a 500 with a stack
     * trace, on the payment path. The column was doing its job and the service
     * was reporting it as a bug in itself.
     *
     * <h2>409, and specifically NOT a retry here</h2>
     *
     * <p>The reflex with an optimistic-lock conflict is to re-read and try
     * again, and it is wrong at this layer. The work these endpoints do is not
     * a pure function of the row: {@code capture} calls a provider and moves
     * real money before it writes anything. Re-running it means a second
     * provider call, so an automatic retry would turn a lock conflict - which is
     * the mechanism working - into the double charge it exists to prevent.
     *
     * <p>Retrying is right where the work IS repeatable, which is why the saga's
     * compensation consumer lets this escape into its error handler instead:
     * {@code reverseCapture} is idempotent, re-reading is exactly the correct
     * response, and the provider recognises a reversal it has already performed.
     * Same exception, opposite handling, and the difference is a property of the
     * work rather than of the exception.
     *
     * <p>So the caller is told what happened and left to decide. 409 rather than
     * 500 because nothing is broken; rather than 503 because retrying the same
     * request unchanged may well be wrong.
     *
     * <h2>What this does NOT protect</h2>
     *
     * <p>The version column guards the ROW, not the provider call. Two
     * concurrent captures both read {@code AUTHORIZED}, both pass the state
     * check, and <strong>both call the provider</strong> - only then does one of
     * them lose on version. What stops that being a double charge is the
     * provider's own idempotency on {@code providerRef}, built in 6j. Two
     * independent mechanisms, and the system needs both: this one bounds what
     * the database ends up believing, and that one bounds what the customer is
     * charged.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleConcurrentModification(ObjectOptimisticLockingFailureException ex) {
        // WARN, not ERROR. This is a control reporting a real event, not a
        // failure - and at ERROR it would page somebody for two clients racing
        // each other.
        log.warn("concurrent modification of a payment - one writer lost on version: {}",
                ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Payment modified concurrently");
        problem.setDetail("Another request changed this payment while this one was running. "
                + "Re-read the payment before deciding whether to repeat this request.");
        problem.setProperty(LogFields.ERROR_CODE, "payment_modified_concurrently");
        return problem;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "payment_not_found", "no payment with that id");
    }
}
