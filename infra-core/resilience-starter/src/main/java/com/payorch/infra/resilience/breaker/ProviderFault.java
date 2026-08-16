package com.payorch.infra.resilience.breaker;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.function.Predicate;

import com.payorch.infra.resilience.bulkhead.BulkheadFullException;
import com.payorch.infra.resilience.deadline.DeadlineExceededException;
import com.payorch.infra.resilience.ratelimit.RateLimitedException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Whether a failure is evidence that <strong>the provider</strong> misbehaved.
 *
 * <p>Deliberately a different question from {@code FailureClassifier}'s, and
 * kept as separate code even though the two overlap. The classifier answers "is
 * it safe to retry this"; this answers "should this count against the
 * provider's health". Those diverge in both directions, and conflating them
 * produces breakers that open for the wrong reasons:
 *
 * <table>
 *   <tr><th>Failure</th><th>Retryable?</th><th>Provider's fault?</th></tr>
 *   <tr><td>Connection refused</td><td>yes</td><td><b>yes</b></td></tr>
 *   <tr><td>5xx</td><td>yes</td><td><b>yes</b></td></tr>
 *   <tr><td>Read timeout / deadline abandoned in flight</td><td>yes</td><td><b>yes</b></td></tr>
 *   <tr><td>Deadline expired <i>before sending</i></td><td>yes</td><td><b>no</b></td></tr>
 *   <tr><td>429 Too Many Requests</td><td>yes</td><td><b>no</b></td></tr>
 *   <tr><td>4xx</td><td>no</td><td>no</td></tr>
 *   <tr><td>Business decline</td><td>no</td><td>no</td></tr>
 * </table>
 *
 * <p>The two {@code no} rows in the middle are the ones that matter, and both
 * would be counted by a naive "any exception is a failure" breaker:
 *
 * <ul>
 *   <li><strong>A deadline that expired before sending is our problem.</strong>
 *       It means <em>we</em> were slow - queued, overloaded, out of budget. The
 *       provider was never contacted. Counting it opens the breaker on a
 *       perfectly healthy provider precisely when we are struggling, which
 *       removes the one working dependency we had left.</li>
 *   <li><strong>A 429 means the provider is healthy and we are rude.</strong> It
 *       answered, promptly, to say we are exceeding our rate. Tripping a breaker
 *       on that is backwards, and from 3e we will be generating those ourselves
 *       through the egress limiter.</li>
 * </ul>
 *
 * <p>This is the third distinct use of {@code DeadlineExceededException}'s
 * {@code wasStarted} flag - state selection in 3a, retry classification in 3b,
 * and provider attribution here.
 */
public final class ProviderFault implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            Boolean verdict = testOne(current);
            if (verdict != null) {
                return verdict;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        // Unrecognised failures do not count against the provider. An
        // unattributable error is more likely to be ours than theirs, and a
        // breaker that opens on our own bugs takes out a working dependency for
        // a reason nobody can diagnose from the breaker's own metrics.
        return false;
    }

    /** @return null when this exception says nothing, so the cause walk continues */
    private Boolean testOne(Throwable failure) {
        if (failure instanceof DeadlineExceededException deadline) {
            // Started and abandoned: the provider was too slow. Never started:
            // we were.
            return deadline.wasStarted();
        }
        if (failure instanceof BulkheadFullException) {
            // Our own admission control refused to send. Explicit rather than
            // left to the default, because the consequence of getting it wrong
            // is severe and silent: the breaker would open on a healthy provider
            // whenever WE were saturated, removing the working dependency at
            // exactly the wrong moment - and it would look like the provider's
            // fault in every dashboard.
            return false;
        }
        if (failure instanceof RateLimitedException) {
            // Our own egress limiter, and the same trap with a sharper edge:
            // this limit exists because the provider asked us to respect it, so
            // counting it as their fault would open a circuit against a provider
            // for the offence of having a contract. Worse, it is self-sustaining
            // - throttling raises the fault rate, the breaker opens, and the
            // dashboard blames the one party that behaved correctly.
            return false;
        }
        if (failure instanceof ConnectException
                || failure instanceof UnknownHostException
                || failure instanceof NoRouteToHostException) {
            return true;
        }
        if (failure instanceof HttpClientErrorException clientError) {
            // Every 4xx, 429 included, is a considered answer from a provider
            // that is working.
            return false;
        }
        if (failure instanceof HttpServerErrorException) {
            return true;
        }
        if (failure instanceof SocketTimeoutException
                || failure instanceof ResourceAccessException
                || failure instanceof IOException) {
            return true;
        }
        return null;
    }
}
