package com.payorch.infra.web;

import java.net.URI;

import com.payorch.infra.logging.LogEvent;
import com.payorch.infra.logging.LogFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders errors as RFC-7807 {@code application/problem+json}.
 *
 * <p>Spring already converts its own MVC exceptions to {@link ProblemDetail}
 * when {@code spring.mvc.problemdetails.enabled} is true. This adds the two
 * things that are ours: our {@link ApiException} type, and a catch-all that
 * refuses to leak.
 */
@RestControllerAdvice
public class ProblemDetailHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailHandler.class);

    private static final URI INTERNAL_ERROR = URI.create("https://payorch.dev/problems/internal-error");
    private static final URI PROBLEM_BASE = URI.create("https://payorch.dev/problems/");

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        problem.setType(PROBLEM_BASE.resolve(ex.errorCode()));
        problem.setProperty(LogFields.ERROR_CODE, ex.errorCode());
        attachCorrelationId(problem);

        log.warn("request rejected",
                LogEvent.event()
                        .with(LogFields.ERROR_CODE, ex.errorCode())
                        .with(LogFields.HTTP_STATUS, ex.status().value())
                        .args());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setType(INTERNAL_ERROR);
        problem.setTitle("Internal Server Error");

        // Deliberately a fixed string rather than ex.getMessage(). Exception
        // text is one of the most common ways cardholder data reaches a client:
        // a driver or HTTP library embeds the request body in its message, and
        // that message gets helpfully echoed back. The caller gets the
        // correlation ID and nothing else; the detail stays server-side.
        problem.setDetail("The request could not be completed. Quote the correlation ID when reporting this.");
        attachCorrelationId(problem);

        log.error("unhandled exception",
                LogEvent.event()
                        .with(LogFields.HTTP_STATUS, HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .args(ex));
        return problem;
    }

    private static void attachCorrelationId(ProblemDetail problem) {
        String correlationId = MDC.get(LogFields.CORRELATION_ID);
        if (correlationId != null) {
            problem.setProperty(LogFields.CORRELATION_ID, correlationId);
        }
    }
}
