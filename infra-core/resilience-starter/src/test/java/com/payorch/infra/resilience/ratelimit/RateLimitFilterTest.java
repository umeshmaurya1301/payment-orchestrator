package com.payorch.infra.resilience.ratelimit;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter's contract, and in particular the ordering that 3e's fairness
 * experiment had to discover the hard way.
 */
class RateLimitFilterTest {

    /** Admits the first {@code budget} calls for any key, then refuses. */
    private static final class Countdown implements RateLimiter {
        private final AtomicInteger remaining;
        private final AtomicInteger calls = new AtomicInteger();

        Countdown(int budget) {
            this.remaining = new AtomicInteger(budget);
        }

        @Override
        public Decision tryAcquire(String key, int permits) {
            calls.incrementAndGet();
            return remaining.getAndAdd(-permits) >= permits
                    ? Decision.allowed(remaining.get())
                    : Decision.rejected(0, 250);
        }

        @Override
        public String kind() {
            return "countdown";
        }

        @Override
        public long permitted() {
            return 0;
        }

        @Override
        public long rejected() {
            return 0;
        }

        int calls() {
            return calls.get();
        }
    }

    private static RateLimitFilter filter(RateLimiter merchant, RateLimiter endpoint) {
        return new RateLimitFilter(merchant, endpoint, RateLimitFilterTest::classify, 0);
    }

    private static RateLimitFilter.EndpointCost classify(HttpServletRequest request) {
        return new RateLimitFilter.EndpointCost(
                "POST".equals(request.getMethod())
                        ? EndpointCosts.PAYMENTS_WRITE
                        : EndpointCosts.PAYMENTS_READ,
                1);
    }

    private static MockHttpServletRequest write(String merchantId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        if (merchantId != null) {
            request.setAttribute(RateLimitFilter.MERCHANT_ATTRIBUTE, merchantId);
        }
        return request;
    }

    /**
     * <strong>The regression test for 3e's fairness finding.</strong>
     *
     * <p>The per-merchant bucket must be consulted <em>before</em> the shared
     * endpoint bucket. With the checks the other way round the endpoint bucket is
     * first-come-first-served, so a merchant flooding the service drains it and a
     * quiet merchant is refused before their own untouched allowance is ever
     * looked at. Measured: a well-behaved merchant sending 10 rps next to one
     * sending 400 succeeded 25% of the time, rising to 99.89% once the order was
     * reversed.
     *
     * <p>Asserted by exhausting the endpoint bucket and checking that a merchant
     * who is over their own limit is told <em>that</em>, which can only happen if
     * their bucket was checked first.
     */
    @Test
    void theMerchantLimitIsCheckedBeforeTheSharedEndpointLimit() throws Exception {
        Countdown merchant = new Countdown(0);      // this merchant is over their allowance
        Countdown endpoint = new Countdown(0);      // and the service is at capacity too
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(merchant, endpoint).doFilter(write("merchant-1"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString())
                .as("the caller must be told it is their own allowance, not the platform's")
                .contains("merchant_rate_limited");
        assertThat(endpoint.calls())
                .as("the shared bucket must not even be consulted for a merchant already over")
                .isZero();
    }

    @Test
    void aMerchantWithinTheirAllowanceStillMeetsTheEndpointLimit() throws Exception {
        Countdown merchant = new Countdown(10);
        Countdown endpoint = new Countdown(0);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(merchant, endpoint).doFilter(write("merchant-1"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString())
                .as("the process is still bounded - merchant-first gives up nothing")
                .contains("endpoint_rate_limited");
    }

    @Test
    void anAdmittedRequestReachesTheChainAndCarriesItsRemainingBudget() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(new Countdown(10), new Countdown(10))
                .doFilter(write("merchant-1"), response, chain);

        assertThat(chain.getRequest()).as("the request must actually proceed").isNotNull();
        assertThat(response.getHeader("X-RateLimit-Remaining")).isNotNull();
    }

    /**
     * A limiter that says only "no" leaves the client to guess when to return,
     * and clients guess by retrying at once - which turns one rejection into a
     * hot loop and makes the limiter the cause of the load it is shedding.
     */
    @Test
    void aRejectionCarriesRetryAfterAndAProblemDocument() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(new Countdown(0), new Countdown(10))
                .doFilter(write("merchant-1"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After"))
                .as("never absent, and never zero - a client told to retry in 0s has been told nothing")
                .isNotNull()
                .isNotEqualTo("0");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"status\":429", "retryAfterMs");
    }

    /**
     * An unauthenticated request has no merchant to key on. It must still be
     * subject to the endpoint limit rather than skipping admission control
     * entirely - otherwise the cheapest way past the limiter is to omit a header.
     */
    @Test
    void aRequestWithNoMerchantStillMeetsTheEndpointLimit() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(new Countdown(0), new Countdown(0))
                .doFilter(write(null), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("endpoint_rate_limited");
    }

    /**
     * Actuator must stay unlimited. Throttling health and metrics means losing
     * observability exactly when load is highest, which is the one moment the
     * numbers are worth having.
     */
    @Test
    void actuatorIsNotRateLimited() throws Exception {
        Countdown endpoint = new Countdown(0);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/actuator/prometheus");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(new Countdown(0), endpoint).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(endpoint.calls()).isZero();
    }

    @Test
    void readsAndWritesSpendFromDifferentBuckets() throws Exception {
        // The write bucket is empty, the read bucket is not. A GET must still be
        // served: a flood of writes should degrade writes and nothing else.
        RateLimiter endpoints = new EndpointRateLimiter(
                java.util.Map.of(EndpointCosts.PAYMENTS_WRITE, new Countdown(0),
                        EndpointCosts.PAYMENTS_READ, new Countdown(10)),
                new UnlimitedRateLimiter());

        MockHttpServletRequest read = new MockHttpServletRequest("GET", "/v1/payments/abc");
        read.setAttribute(RateLimitFilter.MERCHANT_ATTRIBUTE, "merchant-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(new Countdown(10), endpoints).doFilter(read, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
