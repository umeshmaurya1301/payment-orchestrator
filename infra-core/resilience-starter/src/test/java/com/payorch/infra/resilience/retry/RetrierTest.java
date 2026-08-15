package com.payorch.infra.resilience.retry;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.payorch.infra.resilience.deadline.Deadline;
import com.payorch.infra.resilience.deadline.DeadlineExceededException;
import com.payorch.infra.resilience.deadline.Deadlines;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrierTest {

    private static final String REFERENCE = "attempt-0192abcd";

    private final RetryBudget budget = new RetryBudget(100, 0.1);
    private final Retrier retrier = new Retrier(
            2, 50, new FailureClassifier(), budget, new Backoff(1, 4));

    // --- classification ---------------------------------------------------

    @Test
    void aDefiniteRejectionIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retrier.call("authorize", REFERENCE, () -> {
            calls.incrementAndGet();
            throw HttpClientErrorException.create(
                    HttpStatus.BAD_REQUEST, "Bad Request", null, null, null);
        })).isInstanceOf(HttpClientErrorException.class);

        assertThat(calls).as("a 400 is permanent; the next attempt is byte-identical").hasValue(1);
        assertThat(retrier.refusedByClassification()).isEqualTo(1);
    }

    /**
     * The default is not to retry. An exception nobody classified must not be
     * retried on the assumption that it is probably fine - for a payment the
     * cost of guessing wrong is a duplicate charge.
     */
    @Test
    void anUnrecognisedFailureIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retrier.call("authorize", REFERENCE, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("something nobody anticipated");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(calls).hasValue(1);
    }

    @Test
    void aConnectionThatWasNeverEstablishedIsRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        String result = retrier.call("authorize", REFERENCE, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new ConnectException("connection refused");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        assertThat(retrier.succeededAfterRetry()).isEqualTo(1);
    }

    @Test
    void aServerErrorIsRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        String result = retrier.call("authorize", REFERENCE, () -> {
            if (calls.incrementAndGet() < 2) {
                throw HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, null, null);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    void retriesStopAtTheConfiguredMaximum() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retrier.call("authorize", REFERENCE, () -> {
            calls.incrementAndGet();
            throw new ConnectException("refused");
        })).isInstanceOf(ConnectException.class);

        assertThat(calls).as("one initial attempt plus two retries").hasValue(3);
    }

    // --- the safety property ----------------------------------------------

    /**
     * <strong>The assertion this sub-step exists for.</strong>
     *
     * <p>A failure that may already have been processed is only retryable if the
     * provider can recognise the repeat. Without a reference the retry is
     * refused - and refused structurally, not by convention, because the
     * alternative is a comment asking future callers to be careful.
     */
    @Test
    void aFailureThatMayHaveBeenProcessedIsNotRetriedWithoutAReference() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retrier.call("authorize", null, () -> {
            calls.incrementAndGet();
            throw new SocketTimeoutException("read timed out");
        })).isInstanceOf(SocketTimeoutException.class);

        assertThat(calls).as("no retry: this may already have charged a card").hasValue(1);
        assertThat(retrier.refusedWithoutReference()).isEqualTo(1);
    }

    @Test
    void theSameFailureIsRetriedWhenAReferenceIsSupplied() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        String result = retrier.call("authorize", REFERENCE, () -> {
            if (calls.incrementAndGet() < 2) {
                throw new SocketTimeoutException("read timed out");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    /**
     * 3a's `wasStarted` flag doing the work it was built for: the same exception
     * type classifies differently depending on whether anything was sent.
     */
    @Test
    void aDeadlineThatNeverStartedIsSafeToRetryWithoutAReference() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        String result = retrier.call("authorize", null, () -> {
            if (calls.incrementAndGet() < 2) {
                throw DeadlineExceededException.notStarted("authorize", 10, 50);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
        assertThat(retrier.refusedWithoutReference()).isZero();
    }

    @Test
    void aDeadlineAbandonedInFlightNeedsAReference() {
        assertThatThrownBy(() -> retrier.call("authorize", null, () -> {
            throw DeadlineExceededException.abandoned("authorize", 5_000);
        })).isInstanceOf(DeadlineExceededException.class);

        assertThat(retrier.refusedWithoutReference()).isEqualTo(1);
    }

    // --- the budget -------------------------------------------------------

    /**
     * The gate that turns a partial outage into a recoverable one. With a 10%
     * ratio and a bucket of 5, a run of failures exhausts the budget and further
     * retries are refused however retryable the failures are.
     */
    @Test
    void theBudgetStopsARetryStorm() {
        RetryBudget small = new RetryBudget(5, 0.1);
        Retrier limited = new Retrier(3, 50, new FailureClassifier(), small, new Backoff(1, 2));
        AtomicInteger calls = new AtomicInteger();

        for (int i = 0; i < 20; i++) {
            try {
                limited.call("authorize", REFERENCE, () -> {
                    calls.incrementAndGet();
                    throw new ConnectException("refused");
                });
            } catch (Exception expected) {
                // every call fails; the question is how many times it was tried
            }
        }

        // 20 requests contribute 2.0 tokens; the bucket started with 5. So about
        // 7 retries are affordable, against the 60 a naive 3-retry loop would
        // have fired.
        assertThat(limited.budget().grantedRetries()).isBetween(5L, 9L);
        assertThat(limited.budget().deniedRetries()).isPositive();
        assertThat(calls.get())
                .as("20 requests plus a bounded number of retries, not 20 x 4")
                .isLessThan(35);
    }

    @Test
    void theBudgetRefillsWithTraffic() {
        RetryBudget replenishing = new RetryBudget(10, 0.5);
        for (int i = 0; i < 100; i++) {
            replenishing.onRequest();
        }

        assertThat(replenishing.availableTokens())
                .as("capped at maxTokens, so quiet periods cannot bank a storm")
                .isEqualTo(10.0);
    }

    // --- composition with 3a ----------------------------------------------

    /**
     * The two components must not fight. A backoff that would sleep through the
     * remaining budget and then start a call the deadline instantly abandons
     * burns the wait, the connection and the provider's capacity to produce
     * nothing.
     */
    @Test
    void aRetryThatCannotFitInTheRemainingBudgetIsNotAttempted() throws Exception {
        // The floor, not the backoff, is what makes this deterministic. Full
        // jitter draws the delay from [0, ceiling], so a large ceiling still
        // produces a short delay sometimes - an earlier version of this test
        // asserted against a 400ms backoff and failed intermittently, which is
        // the jitter behaving exactly as designed and the test asserting the
        // wrong thing. A 5s floor cannot fit in a 100ms budget for any draw.
        Retrier slow = new Retrier(3, 5_000, new FailureClassifier(), budget, new Backoff(1, 1));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> Deadlines.runWith(Deadline.of(100), () ->
                slow.call("authorize", REFERENCE, () -> {
                    calls.incrementAndGet();
                    throw new ConnectException("refused");
                })))
                .isInstanceOf(ConnectException.class);

        assertThat(calls).as("no room for a backoff plus a call, so no retry").hasValue(1);
        assertThat(slow.refusedByDeadline()).isEqualTo(1);
    }

    /**
     * The other side of the same gate: when there <em>is</em> room, the retry
     * proceeds. Without this, the test above would pass just as well against a
     * retrier that never retried at all.
     */
    @Test
    void aRetryThatFitsInTheRemainingBudgetProceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        String result = Deadlines.runWith(Deadline.of(10_000), () ->
                retrier.call("authorize", REFERENCE, () -> {
                    if (calls.incrementAndGet() < 2) {
                        throw new ConnectException("refused");
                    }
                    return "ok";
                }));

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
        assertThat(retrier.refusedByDeadline()).isZero();
    }

    @Test
    void withNoDeadlineBoundRetriesProceed() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        String result = retrier.call("authorize", REFERENCE, () -> {
            if (calls.incrementAndGet() < 2) {
                throw new IOException("connection reset");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
    }
}
