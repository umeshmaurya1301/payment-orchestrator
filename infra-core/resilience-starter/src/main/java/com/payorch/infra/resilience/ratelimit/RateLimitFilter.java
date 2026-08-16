package com.payorch.infra.resilience.ratelimit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ingress admission control: the layer 3d proved was missing.
 *
 * <p>3d's bulkhead bounded concurrency at the PSP call and {@code payments-edge}
 * ran out of heap anyway - twice without it, once with. The reason was siting:
 * by the time the connector refuses, the edge has already spent a request
 * thread, a pooled connection and a parsed body on that request. The only place
 * those can be saved is before they are spent, which is here.
 *
 * <h2>Two limits, merchant first</h2>
 *
 * <ol>
 *   <li><strong>Merchant</strong> - fairness. A caller over their own allowance
 *       is refused here, before they can spend anything shared.</li>
 *   <li><strong>Endpoint</strong> - service-wide, sized to what this process can
 *       survive. A backstop over the sum of merchants who are each individually
 *       within their allowance, because fifty polite merchants still add up.</li>
 * </ol>
 *
 * <p><strong>This order was measured, and the obvious one is wrong.</strong> The
 * first version checked the endpoint limit first, reasoning that the limit
 * protecting the process should be the one that cannot be bypassed. It has a
 * defect that only shows up with more than one merchant: the endpoint bucket is
 * first-come-first-served, so a merchant sending 400 rps wins the race for
 * shared capacity against one sending 10 rps, and the per-merchant limiter never
 * gets consulted because its victim was already refused upstream of it.
 *
 * <p>Measured in {@code docs/experiments/05-rate-limiters.md}: with the endpoint
 * check first, a well-behaved merchant sending 10 rps alongside a runaway
 * neighbour succeeded <strong>25%</strong> of the time - the limiters were
 * working, the process was safe, and the fairness they were added for did not
 * exist. Checking the merchant bucket first throttles the noisy merchant to
 * their own allowance before they can reach the shared bucket at all.
 *
 * <p>Nothing is given up. Whatever passes the per-merchant check still meets the
 * endpoint limit, so the process is bounded either way; the cost is one extra
 * Redis round trip for requests that would have been refused by the endpoint
 * gate anyway.
 *
 * <p>The two are counted separately regardless of order: a 429 from the endpoint
 * limit means "the service is at capacity" and one from the merchant limit means
 * "you are over your allowance". Those need different responses from the
 * operator and from the merchant.
 *
 * <h2>Where it sits in the chain</h2>
 *
 * <p>After authentication, which is not the cheapest possible ordering - it
 * costs a merchant lookup on a request that is about to be refused. It is
 * nevertheless the correct one, because the per-merchant bucket is keyed on an
 * <em>authenticated</em> identity. Rate limiting on an unauthenticated header
 * means the key is attacker-controlled, and a limiter whose key an attacker
 * chooses is not a limiter: send a fresh value each time and every request gets
 * a full bucket.
 */
public class RateLimitFilter extends OncePerRequestFilter implements Ordered {

    /** Set by the edge's auth filter. Named here to avoid depending on the service. */
    public static final String MERCHANT_ATTRIBUTE = "payorch.merchantId";

    private static final String API_PREFIX = "/v1/";

    private final RateLimiter merchantLimiter;
    private final RateLimiter endpointLimiter;
    private final Function<HttpServletRequest, EndpointCost> classifier;
    private final int order;

    /**
     * @param classifier maps a request to the bucket it spends from and what it
     *                   costs. A function rather than a map of URL patterns
     *                   because the cost of an endpoint is a property of the
     *                   service that owns it, not of this library.
     */
    public RateLimitFilter(RateLimiter merchantLimiter,
                           RateLimiter endpointLimiter,
                           Function<HttpServletRequest, EndpointCost> classifier,
                           int order) {
        this.merchantLimiter = merchantLimiter;
        this.endpointLimiter = endpointLimiter;
        this.classifier = classifier;
        this.order = order;
    }

    /**
     * Which endpoint bucket a request spends from, and how much.
     *
     * <p>Cost is not always 1. A status read and an authorisation both arrive as
     * one HTTP request and are nowhere near the same amount of work, so pricing
     * them identically means either throttling polling that is nearly free or
     * admitting writes that are not. Requests-per-second is a proxy for load;
     * where the proxy is known to be wrong, the price is where it gets corrected.
     */
    public record EndpointCost(String bucket, int permits) {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        EndpointCost endpoint = classifier.apply(request);

        // Merchant first. See the class javadoc: with this the other way round a
        // runaway neighbour spends the shared bucket before the limiter that
        // exists to stop them is ever asked.
        Object merchantId = request.getAttribute(MERCHANT_ATTRIBUTE);
        RateLimiter.Decision byMerchant = null;
        if (merchantId != null) {
            byMerchant = merchantLimiter.tryAcquire(merchantId.toString(), endpoint.permits());
            if (!byMerchant.allowed()) {
                reject(response, byMerchant, "merchant_rate_limited",
                        "You have exceeded your request allowance. Retry after the indicated interval.");
                return;
            }
        }

        // A merchant token has already been spent by the time we get here, so a
        // request refused below is charged to an allowance it never used. That
        // is the one cost of this ordering, and it is the right way round: it
        // makes a merchant marginally stricter than their contract during a
        // platform-wide overload, rather than letting them consume shared
        // capacity they were not entitled to.
        RateLimiter.Decision byEndpoint = endpointLimiter.tryAcquire(endpoint.bucket(), endpoint.permits());
        if (!byEndpoint.allowed()) {
            reject(response, byEndpoint, "endpoint_rate_limited",
                    "The service is at capacity for this endpoint. Retry after the indicated interval.");
            return;
        }

        if (byMerchant != null) {
            // Only the merchant's own remaining budget is advertised. The
            // endpoint bucket is a service-wide capacity number and telling a
            // caller how close the platform is to its ceiling invites exactly
            // the behaviour it is defending against.
            response.setHeader("X-RateLimit-Remaining", Long.toString(byMerchant.tokensLeft()));
        }

        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Actuator stays unlimited on purpose. Throttling the health and metrics
        // endpoints means losing observability precisely when load is highest,
        // which is the one moment the numbers are worth having. Phase 2 found
        // the edge's own /actuator/prometheus starved under load; rate limiting
        // it as well would make that permanent rather than incidental.
        return !request.getRequestURI().startsWith(API_PREFIX);
    }

    @Override
    public int getOrder() {
        return order;
    }

    /**
     * RFC 7807, written directly.
     *
     * <p>Same reason as the auth filter: an exception thrown from a filter never
     * reaches {@code @RestControllerAdvice}, and the container's default error
     * page for a REST client is an HTML document.
     *
     * <p><strong>{@code Retry-After} is not decoration.</strong> A limiter that
     * answers only "no" leaves the client to guess when to come back, and
     * clients guess by retrying at once - which turns one rejection into a hot
     * loop and makes the limiter the cause of the load it was added to shed.
     */
    private static void reject(HttpServletResponse response, RateLimiter.Decision decision,
                               String errorCode, String detail) throws IOException {
        long retryAfterSeconds = Math.max(1, (decision.retryAfterMs() + 999) / 1000);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setHeader("X-RateLimit-Remaining", "0");

        String correlationId = MDC.get("correlationId");
        response.getWriter().write("""
                {"type":"https://payorch.dev/problems/%s","title":"Too Many Requests","status":429,\
                "detail":"%s","errorCode":"%s","retryAfterMs":%d,"correlationId":"%s"}"""
                .formatted(errorCode, detail, errorCode, decision.retryAfterMs(),
                        correlationId == null ? "" : correlationId));
    }
}
