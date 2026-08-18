package com.payorch.orchestrator;

import java.util.UUID;

import com.payorch.infra.persistence.Uuid7;
import com.payorch.infra.web.ApiException;
import com.payorch.orchestrator.api.OrchestratorApi;
import com.payorch.orchestrator.domain.PaymentTransitions;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "payment_not_found", "no payment with that id");
    }
}
