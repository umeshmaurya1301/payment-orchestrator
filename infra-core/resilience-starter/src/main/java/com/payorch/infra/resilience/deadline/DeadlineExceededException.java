package com.payorch.infra.resilience.deadline;

/**
 * The request ran out of budget.
 *
 * <p>Carries whether the call was ever <em>started</em>, and that flag is not
 * cosmetic - for a payment it is the difference between two states:
 *
 * <ul>
 *   <li>{@code started == false}: the budget was already too small to bother, so
 *       nothing was sent. The provider was demonstrably not contacted and the
 *       payment is {@code FAILED}.</li>
 *   <li>{@code started == true}: the call went out and was abandoned. The
 *       provider may have authorised the card and the response was thrown away
 *       with the connection. The payment is {@code UNKNOWN}.</li>
 * </ul>
 *
 * <p>Collapsing those two into one "timeout" is precisely the mistake that makes
 * a retry unsafe, because only one of them can be retried without risking a
 * double charge.
 */
public class DeadlineExceededException extends RuntimeException {

    private final boolean started;
    private final long remainingMs;

    private DeadlineExceededException(String message, boolean started, long remainingMs) {
        super(message);
        this.started = started;
        this.remainingMs = remainingMs;
    }

    /** Not enough budget left to start. Nothing was sent. */
    public static DeadlineExceededException notStarted(String operation, long remainingMs, long requiredMs) {
        return new DeadlineExceededException(
                "not starting '" + operation + "': " + remainingMs + "ms remaining, "
                        + requiredMs + "ms required",
                false, remainingMs);
    }

    /** The call was sent and abandoned. The outcome is unknown. */
    public static DeadlineExceededException abandoned(String operation, long afterMs) {
        return new DeadlineExceededException(
                "abandoned '" + operation + "' after " + afterMs + "ms; outcome unknown",
                true, 0);
    }

    /**
     * Whether the downstream may have acted on the request.
     *
     * <p>{@code false} is the safe case and the only one a caller may treat as a
     * definite non-event.
     */
    public boolean wasStarted() {
        return started;
    }

    public long remainingMs() {
        return remainingMs;
    }
}
