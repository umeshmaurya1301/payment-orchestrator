package com.payorch.infra.resilience.deadline;

import java.util.Optional;

/**
 * The current request's {@link Deadline}, carried implicitly.
 *
 * <p><strong>A {@link ScopedValue}, not a {@code ThreadLocal}</strong>, and the
 * reason is virtual threads. Every service here runs on them, so there is one
 * thread per in-flight request rather than one per core - the phase-2 baseline
 * saw 2,896 of them parked at once. A {@code ThreadLocal} on each of those is a
 * strong reference in a per-thread map that lives until the thread ends and
 * that nothing cleans up if a filter forgets its {@code finally}. A
 * {@code ScopedValue} is immutable, bound for exactly the duration of a
 * {@code run}/{@code call}, and unbound automatically when that returns -
 * including when it returns by throwing.
 *
 * <p><strong>The catch, stated up front:</strong> a scoped value is visible to
 * the thread that bound it and to threads forked inside a
 * {@code StructuredTaskScope}, and to nothing else. Hand work to an unrelated
 * executor and the deadline does not travel with it - {@link #current()} returns
 * empty and the work runs unbounded. That is why {@link DeadlineExecutor}
 * captures the deadline explicitly before submitting rather than reading it on
 * the far side.
 */
public final class Deadlines {

    private static final ScopedValue<Deadline> CURRENT = ScopedValue.newInstance();

    private Deadlines() {
    }

    public static Optional<Deadline> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /**
     * The current deadline, or one freshly created with {@code fallbackMs}.
     *
     * <p>Falling back rather than throwing is deliberate. An unbounded call is
     * exactly the failure mode this whole sub-step exists to remove, so code
     * reached outside a request - a scheduled job, a test - should still be
     * bounded by something rather than by nothing.
     */
    public static Deadline currentOrDefault(long fallbackMs) {
        return current().orElseGet(() -> Deadline.of(fallbackMs));
    }

    /**
     * Runs {@code op} with {@code deadline} bound, unbinding it on the way out -
     * including when {@code op} throws.
     *
     * <p>Deliberately the only overload. A companion taking a {@code Runnable}
     * reads as an obvious convenience and makes {@code runWith(d, () ->
     * something())} ambiguous, because an expression lambda satisfies both. The
     * compiler error that produces is opaque enough to be worth designing out.
     */
    public static <T> T runWith(Deadline deadline, ScopedValue.CallableOp<T, Exception> op)
            throws Exception {
        return ScopedValue.where(CURRENT, deadline).call(op);
    }
}
