package com.payorch.connector;

import com.payorch.connector.api.ConnectorApi;
import com.payorch.connector.provider.PspAdapter;
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
