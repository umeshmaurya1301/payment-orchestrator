package com.payorch.infra.resilience.retry;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.LongAdder;

import com.payorch.infra.resilience.deadline.Deadline;
import com.payorch.infra.resilience.deadline.Deadlines;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retries, subject to four independent gates.
 *
 * <p>Every one of them can veto, and each exists because of a distinct way
 * retries go wrong:
 *
 * <ol>
 *   <li><strong>Classification.</strong> Is this failure retryable at all?
 *       Default is no.</li>
 *   <li><strong>An idempotency reference.</strong> If the failure might have
 *       been processed, a retry is only safe when the provider can recognise it
 *       as the same request. Enforced, not assumed - see below.</li>
 *   <li><strong>The budget.</strong> Retries are capped at a fraction of total
 *       traffic, so a partial outage cannot be amplified into a total one.</li>
 *   <li><strong>The deadline.</strong> A retry that cannot finish inside the
 *       remaining budget is pure waste: it consumes a connection and a slot in
 *       the provider's pool to produce a result nobody will still be waiting
 *       for.</li>
 * </ol>
 *
 * <p>Gate 2 is the one worth dwelling on. {@link #call} <em>requires</em> an
 * idempotency reference as a parameter, and refuses to retry a
 * {@link FailureClass#RETRY_WITH_SAME_REFERENCE} failure without one. The
 * alternative - documenting that callers should reuse their reference - is a
 * comment, and a comment does not stop the person who adds a second call site
 * next year from generating a fresh id and double-charging a card. Making the
 * reference a required argument means the unsafe call does not compile.
 */
public class Retrier {

    private static final Logger log = LoggerFactory.getLogger(Retrier.class);

    private final int maxRetries;
    private final long minSliceMs;
    private final FailureClassifier classifier;
    private final RetryBudget budget;
    private final Backoff backoff;

    private final LongAdder attempted = new LongAdder();
    private final LongAdder succeededAfterRetry = new LongAdder();
    private final LongAdder refusedByClass = new LongAdder();
    private final LongAdder refusedByDeadline = new LongAdder();
    private final LongAdder refusedWithoutReference = new LongAdder();

    public Retrier(int maxRetries,
                   long minSliceMs,
                   FailureClassifier classifier,
                   RetryBudget budget,
                   Backoff backoff) {
        this.maxRetries = Math.max(maxRetries, 0);
        this.minSliceMs = minSliceMs;
        this.classifier = classifier;
        this.budget = budget;
        this.backoff = backoff;
    }

    /**
     * @param operation            a name for logs and metrics
     * @param idempotencyReference the identifier the downstream uses to
     *                             recognise a repeat. <strong>Must be the same
     *                             value across retries</strong> - which it is,
     *                             because it is passed once and the work closure
     *                             is unchanged. Null or blank disables any retry
     *                             that could duplicate work.
     */
    public <T> T call(String operation, String idempotencyReference, Callable<T> work) {
        return call(operation, idempotencyReference, maxRetries, work);
    }

    /**
     * The same, with the retry ceiling supplied per call.
     *
     * <p>Added in 3f. How many attempts a failure earns depends on how likely a
     * failure is to be transient, and that is a property of the provider: at 96%
     * success a failure is usually bad luck and worth another attempt, while at
     * 99.9% a failure much more often means something is genuinely wrong and a
     * retry is load added to a provider already in trouble.
     *
     * <p>The retry budget is unchanged and still global. It has to be: it is a
     * statement about the total extra load this service is willing to generate,
     * and a per-provider budget would let three providers each spend 10% and
     * produce 30%.
     *
     * @param maxRetries this provider's ceiling, from {@code psp_config}
     */
    public <T> T call(String operation, String idempotencyReference, int maxRetries, Callable<T> work) {
        budget.onRequest();

        int retry = 0;
        while (true) {
            try {
                T result = work.call();
                if (retry > 0) {
                    succeededAfterRetry.increment();
                    log.info("'{}' succeeded on retry {}", operation, retry);
                }
                return result;

            } catch (Exception failure) {
                if (retry >= maxRetries || !mayRetry(operation, failure, idempotencyReference, retry)) {
                    throw rethrow(failure);
                }

                long delayMs = backoff.delayMs(retry);
                if (!fitsInDeadline(operation, delayMs)) {
                    refusedByDeadline.increment();
                    throw rethrow(failure);
                }

                attempted.increment();
                log.warn("retrying '{}' in {}ms (retry {} of {})",
                        operation, delayMs, retry + 1, maxRetries);
                sleep(delayMs);
                retry++;
            }
        }
    }

    private boolean mayRetry(String operation, Throwable failure, String reference, int retry) {
        FailureClass classified = classifier.classify(failure);

        if (classified == FailureClass.NONE) {
            refusedByClass.increment();
            return false;
        }

        if (classified == FailureClass.RETRY_WITH_SAME_REFERENCE
                && (reference == null || reference.isBlank())) {
            // Loud, because this is a programming error rather than a runtime
            // condition: someone asked for a retry of something that might
            // already have happened, without the one thing that makes it safe.
            refusedWithoutReference.increment();
            log.error("refusing to retry '{}': the failure may have been processed and no "
                    + "idempotency reference was supplied", operation);
            return false;
        }

        if (!budget.tryAcquire()) {
            // Not logged per occurrence: when the budget bites it bites for
            // every request at once, and a log line each would bury the reason.
            return false;
        }

        log.debug("'{}' failure classified {} on retry {}", operation, classified, retry);
        return true;
    }

    /**
     * A retry must fit: the backoff delay, plus enough left afterwards to be
     * worth starting a call.
     *
     * <p>Without this the two components fight. Backoff would happily sleep
     * through the remainder of the budget and then start a call the deadline
     * immediately abandons - burning the wait, the connection and the
     * provider's capacity to produce nothing.
     */
    private boolean fitsInDeadline(String operation, long delayMs) {
        Deadline deadline = Deadlines.current().orElse(null);
        if (deadline == null) {
            return true;
        }
        long needed = delayMs + minSliceMs;
        if (deadline.hasAtLeast(needed)) {
            return true;
        }
        log.warn("not retrying '{}': {}ms remaining, {}ms needed for backoff plus a call",
                operation, deadline.remainingMs(), needed);
        return false;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off", e);
        }
    }

    /**
     * Rethrows the original failure with its type intact.
     *
     * <p>The obvious implementation wraps a checked exception in an
     * {@code IllegalStateException}, and it is wrong in two ways that its own
     * tests caught. It destroys the type {@link FailureClassifier} reads, so an
     * outer retry or breaker would classify every failure as unrecognised; and
     * it breaks callers - {@code psp-connector} catches
     * {@code RestClientException} to convert it into "the provider did not
     * answer", and a wrapper walks straight past that catch into the generic
     * 500 handler. A payment would go from {@code UNKNOWN} to an unhandled
     * error purely because it passed through a retry.
     *
     * <p>{@code DeadlineExecutor} unwraps {@code ExecutionException} for exactly
     * the same reason. The cost is the sneaky-throw idiom below: a checked
     * exception escapes a method that does not declare it. That is a real
     * trade-off, taken deliberately, because the alternative is losing the
     * information every layer above this one needs.
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E {
        throw (E) failure;
    }

    public long retriesAttempted() {
        return attempted.sum();
    }

    /** The number that says the retry is earning its keep. */
    public long succeededAfterRetry() {
        return succeededAfterRetry.sum();
    }

    public long refusedByClassification() {
        return refusedByClass.sum();
    }

    public long refusedByDeadline() {
        return refusedByDeadline.sum();
    }

    public long refusedWithoutReference() {
        return refusedWithoutReference.sum();
    }

    public RetryBudget budget() {
        return budget;
    }
}
