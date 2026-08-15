package com.payorch.infra.logging;

import java.util.Set;

/**
 * The log field schema. Every service uses these names and no others.
 *
 * <p>Consistent field names are what make a log store queryable. If one service
 * writes {@code merchantId} and another writes {@code merchant_id} and a third
 * writes {@code mid}, no single query spans a request, and the whole pipeline
 * built in phase 4 is worth much less than it cost.
 *
 * <p>This set is also the allowlist enforced by {@link LogEvent}: fields are
 * named explicitly and individually rather than serializing an object and
 * scrubbing it afterwards. A denylist fails silently the first time someone
 * adds a field to a DTO; an allowlist fails loudly, which is the behaviour we
 * want from a control protecting card data.
 */
public final class LogFields {

    // --- correlation ------------------------------------------------------
    public static final String CORRELATION_ID = "correlationId";
    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";

    // --- domain -----------------------------------------------------------
    public static final String MERCHANT_ID = "merchantId";
    public static final String PAYMENT_ID = "paymentId";
    public static final String ATTEMPT_NO = "attemptNo";
    public static final String STATE = "state";
    public static final String PREVIOUS_STATE = "previousState";
    public static final String IDEMPOTENCY_KEY = "idempotencyKey";
    public static final String AMOUNT_MINOR = "amountMinor";
    public static final String CURRENCY = "currency";

    // --- instrument (never the PAN itself - see the tokenization boundary) --
    public static final String TOKEN = "token";
    public static final String BIN = "bin";
    public static final String LAST4 = "last4";

    // --- provider ---------------------------------------------------------
    public static final String PSP_ID = "pspId";
    public static final String OPERATION = "operation";

    // --- outcome ----------------------------------------------------------
    public static final String OUTCOME = "outcome";
    public static final String ERROR_CODE = "errorCode";
    public static final String HTTP_STATUS = "httpStatus";
    public static final String LATENCY_MS = "latencyMs";
    public static final String DEADLINE_REMAINING_MS = "deadlineRemainingMs";

    /** Every name that may appear as a structured log field. */
    public static final Set<String> ALLOWED = Set.of(
            CORRELATION_ID, TRACE_ID, SPAN_ID,
            MERCHANT_ID, PAYMENT_ID, ATTEMPT_NO, STATE, PREVIOUS_STATE,
            IDEMPOTENCY_KEY, AMOUNT_MINOR, CURRENCY,
            TOKEN, BIN, LAST4,
            PSP_ID, OPERATION,
            OUTCOME, ERROR_CODE, HTTP_STATUS, LATENCY_MS, DEADLINE_REMAINING_MS);

    private LogFields() {
    }
}
