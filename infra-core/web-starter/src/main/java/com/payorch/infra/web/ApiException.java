package com.payorch.infra.web;

import org.springframework.http.HttpStatus;

/**
 * An error that is safe to describe to the caller.
 *
 * <p>The distinction this type draws is the important part. An
 * {@code ApiException} carries a {@code detail} that has been consciously
 * written for external consumption; anything else that escapes a controller is
 * treated as untrusted text and never echoed back - see
 * {@link ProblemDetailHandler}.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public ApiException(HttpStatus status, String errorCode, String detail) {
        super(detail);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus status() {
        return status;
    }

    /** Stable, machine-readable code. Clients branch on this, not on the message. */
    public String errorCode() {
        return errorCode;
    }
}
