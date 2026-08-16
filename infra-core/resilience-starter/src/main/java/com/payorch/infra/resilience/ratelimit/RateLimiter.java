package com.payorch.infra.resilience.ratelimit;

/**
 * Bounds the <em>rate</em> at which work is admitted, per key.
 *
 * <p>The distinction from {@link com.payorch.infra.resilience.bulkhead.Bulkhead}
 * is worth being exact about, because 3d proved they are not substitutes. A
 * bulkhead bounds <strong>concurrency</strong> - how many calls exist at once -
 * and therefore silently tracks latency: when the provider slows down, the same
 * limit admits proportionally fewer requests per second. A rate limiter bounds
 * <strong>arrivals per second</strong> and is indifferent to how long each one
 * takes.
 *
 * <p>That indifference is exactly why 3e exists. The bulkhead in 3d could not
 * save {@code payments-edge} from running out of heap, because the edge's
 * resources are consumed at <em>admission</em> - a request thread, a pooled
 * connection, a parsed body - long before the connector is asked whether it has
 * capacity. Only something that refuses at the door can bound that, and refusing
 * at the door is a statement about arrival rate.
 *
 * <h2>Three layers, three different jobs</h2>
 *
 * <ul>
 *   <li><strong>Ingress, per merchant</strong> ({@code payments-edge}) - fairness.
 *       One merchant's runaway retry loop must not consume the capacity every
 *       other merchant paid for.</li>
 *   <li><strong>Per endpoint</strong> ({@code payments-edge}) - cost. A status
 *       read is not an authorisation, and pricing them the same either throttles
 *       polling that is nearly free or admits writes that are not.</li>
 *   <li><strong>Egress, per PSP</strong> ({@code psp-connector}) - <em>their</em>
 *       contract, not ours. This is the layer that protects the downstream from
 *       us, so that we are not the reason a provider blocks the account.</li>
 * </ul>
 */
public interface RateLimiter {

    /**
     * @param key     the bucket - a merchant id, an endpoint, a provider id
     * @param permits how much this request costs. Not always 1: an endpoint that
     *                does real work can be priced above one that does not.
     */
    Decision tryAcquire(String key, int permits);

    default Decision tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    /** {@code atomic-lua}, {@code read-modify-write} or {@code local}. Reported in metrics. */
    String kind();

    long permitted();

    long rejected();

    /**
     * The answer, and enough for the caller to say something useful.
     *
     * @param allowed      whether the request may proceed
     * @param tokensLeft   remaining budget, for {@code X-RateLimit-Remaining}
     * @param retryAfterMs how long until a token exists, for {@code Retry-After}.
     *                     A limiter that says only "no" forces the client to
     *                     guess, and clients guess by retrying immediately -
     *                     which turns one rejection into a hot loop and makes
     *                     the limiter the cause of the load it is shedding.
     */
    record Decision(boolean allowed, long tokensLeft, long retryAfterMs) {

        public static Decision allowed(long tokensLeft) {
            return new Decision(true, tokensLeft, 0);
        }

        public static Decision rejected(long tokensLeft, long retryAfterMs) {
            return new Decision(false, tokensLeft, retryAfterMs);
        }
    }
}
