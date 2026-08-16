package com.payorch.infra.resilience.retry;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import com.payorch.infra.resilience.bulkhead.BulkheadFullException;
import com.payorch.infra.resilience.deadline.DeadlineExceededException;
import com.payorch.infra.resilience.ratelimit.RateLimitedException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Decides what a failure permits, by type.
 *
 * <p><strong>The default is {@link FailureClass#NONE}.</strong> An unrecognised
 * failure is not retried. That is an allowlist, in the same spirit as
 * {@code LogFields}: a denylist would retry every exception nobody thought
 * about, and for a payment the cost of retrying something that should not have
 * been retried is a duplicate charge, while the cost of not retrying something
 * that could have been is one avoidable failure.
 *
 * <p>Asymmetric costs mean an asymmetric default.
 */
public class FailureClassifier {

    /**
     * Walks the cause chain, because the interesting exception is almost never
     * the outermost one - a {@code ResourceAccessException} wrapping a
     * {@code SocketTimeoutException} says nothing on its own, and the wrapper is
     * what the caller actually catches.
     */
    public FailureClass classify(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            FailureClass classified = classifyOne(current);
            if (classified != null) {
                return classified;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return FailureClass.NONE;
    }

    /** @return null when this exception says nothing, so the walk continues */
    protected FailureClass classifyOne(Throwable failure) {

        // The deadline already knows the answer, because 3a made it carry the
        // one fact that matters: was anything sent.
        if (failure instanceof DeadlineExceededException deadline) {
            return deadline.wasStarted()
                    ? FailureClass.RETRY_WITH_SAME_REFERENCE
                    : FailureClass.SAFE;
        }

        if (failure instanceof BulkheadFullException) {
            // Nothing was sent, so retrying could not duplicate anything - and
            // it is still the wrong thing to do. A bulkhead rejection means the
            // system is already at its concurrency limit; retrying adds load to
            // a saturated system and takes a permit a fresh request could have
            // used. Shedding load means shedding it.
            return FailureClass.NONE;
        }

        if (failure instanceof RateLimitedException) {
            // Our own egress limiter. Nothing was sent, so a retry could not
            // duplicate anything - and retrying spends the request's deadline
            // arguing with arithmetic we control. The limit will not have moved
            // by the time the backoff elapses, because we are the one enforcing
            // it. Phase 5 routes this elsewhere instead.
            return FailureClass.NONE;
        }

        // Never connected. The provider has not seen a byte, so nothing it
        // could have done is at risk of being done twice.
        if (failure instanceof ConnectException
                || failure instanceof UnknownHostException
                || failure instanceof NoRouteToHostException) {
            return FailureClass.SAFE;
        }

        if (failure instanceof HttpClientErrorException clientError) {
            return switch (clientError.getStatusCode().value()) {
                // Rejected before processing, by definition of the status. The
                // provider is telling us it did not do the work.
                case 429, 408 -> FailureClass.SAFE;
                // Every other 4xx is our fault and permanent: a malformed body,
                // a bad credential, an unknown token. The next attempt is
                // byte-identical and will fail identically.
                default -> FailureClass.NONE;
            };
        }

        // A 5xx means the provider had a problem, and says nothing about how far
        // it got before having it. It may have authorised the card and failed
        // while writing the response.
        if (failure instanceof HttpServerErrorException) {
            return FailureClass.RETRY_WITH_SAME_REFERENCE;
        }

        // The request went out and the answer did not come back. This is the
        // classic double-charge shape, and the only thing that makes it
        // retryable is the provider recognising the reference.
        if (failure instanceof SocketTimeoutException) {
            return FailureClass.RETRY_WITH_SAME_REFERENCE;
        }

        // Spring's wrapper for I/O problems. Deliberately checked after the
        // specific types above so the cause decides where it can.
        if (failure instanceof ResourceAccessException || failure instanceof IOException) {
            return FailureClass.RETRY_WITH_SAME_REFERENCE;
        }

        return null;
    }
}
