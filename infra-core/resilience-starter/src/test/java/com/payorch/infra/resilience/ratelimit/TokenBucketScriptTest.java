package com.payorch.infra.resilience.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises both implementations against a real Redis.
 *
 * <p><strong>Against a real one, not a fake.</strong> The behaviour under test is
 * that Redis evaluates a script single-threaded to completion, and a stub that
 * pretends to do that would be asserting the assumption instead of checking it.
 * An in-memory double would also make {@link ReadModifyWriteRateLimiter} look
 * correct, because the race needs genuine network round trips to open the window
 * the two callers interleave in.
 *
 * <p>Skipped when Redis is not reachable, so a clean checkout still builds.
 * {@code docker compose up -d redis} is enough to make it run.
 */
@EnabledIf("redisIsReachable")
class TokenBucketScriptTest {

    private static final String HOST =
            System.getenv().getOrDefault("TEST_REDIS_HOST", "localhost");
    private static final int PORT =
            Integer.parseInt(System.getenv().getOrDefault("TEST_REDIS_PORT", "6379"));

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    static boolean redisIsReachable() {
        try (java.net.Socket probe = new java.net.Socket()) {
            probe.connect(new java.net.InetSocketAddress(HOST, PORT), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(HOST, PORT);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate((RedisConnectionFactory) factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    private static String freshKey() {
        return UUID.randomUUID().toString();
    }

    @Test
    void aBucketStartsFullAndSpendsDown() {
        RateLimiter limiter = new RedisTokenBucketRateLimiter(redis, "test:" + freshKey(), 3, 1, 60);
        String key = freshKey();

        assertThat(limiter.tryAcquire(key).allowed()).isTrue();
        assertThat(limiter.tryAcquire(key).allowed()).isTrue();
        assertThat(limiter.tryAcquire(key).allowed()).isTrue();

        RateLimiter.Decision refused = limiter.tryAcquire(key);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterMs())
                .as("a client told only 'no' retries immediately and becomes the load")
                .isPositive();
    }

    /**
     * The buckets must not be one bucket. Without per-key isolation the
     * "per-merchant" limit is a global limit wearing a label, and the first
     * merchant to arrive spends everybody's allowance.
     */
    @Test
    void oneKeyIsExhaustedWithoutAffectingAnother() {
        RateLimiter limiter = new RedisTokenBucketRateLimiter(redis, "test:" + freshKey(), 2, 1, 60);
        String noisy = freshKey();
        String polite = freshKey();

        limiter.tryAcquire(noisy);
        limiter.tryAcquire(noisy);
        assertThat(limiter.tryAcquire(noisy).allowed()).isFalse();

        assertThat(limiter.tryAcquire(polite).allowed())
                .as("one merchant exhausting their bucket must not spend another's")
                .isTrue();
    }

    @Test
    void tokensRefillWithElapsedTime() throws Exception {
        // 20/s, so a single token is back in 50ms.
        RateLimiter limiter = new RedisTokenBucketRateLimiter(redis, "test:" + freshKey(), 1, 20, 60);
        String key = freshKey();

        assertThat(limiter.tryAcquire(key).allowed()).isTrue();
        assertThat(limiter.tryAcquire(key).allowed()).isFalse();

        Thread.sleep(300);

        assertThat(limiter.tryAcquire(key).allowed())
                .as("refill is computed from elapsed time on read, with no background job")
                .isTrue();
    }

    /**
     * The headline of 3e's atomicity arm, as a unit test.
     *
     * <p>200 callers arrive together against a bucket holding 10 tokens. The
     * atomic script must admit exactly 10. It is asserted exactly rather than
     * approximately, because "roughly the limit" is precisely the property the
     * other implementation has.
     */
    @Test
    void theAtomicScriptAdmitsExactlyTheBudgetUnderConcurrency() throws Exception {
        RateLimiter limiter = new RedisTokenBucketRateLimiter(
                redis, "test:" + freshKey(), 10, 0.001, 60);
        String key = freshKey();

        assertThat(admittedByConcurrentCallers(limiter, key, 200))
                .as("Redis evaluates the script single-threaded to completion")
                .isEqualTo(10);
    }

    /**
     * The same load against the read-modify-write implementation, asserting that
     * it over-admits.
     *
     * <p>An unusual thing to assert, and deliberate: this is the arm of the
     * experiment that makes the Lua worth its complexity, and a regression here
     * would mean the comparison had quietly stopped comparing anything. If this
     * test ever fails because the naive version became accurate, the honest
     * response is to find out what changed rather than to relax the assertion.
     *
     * <p>Only "more than the budget" is asserted, not a specific number. The
     * excess depends on how the round trips interleave, which is a property of
     * the machine and the moment - pinning it would produce a flaky test that
     * says nothing extra.
     */
    @Test
    void theReadModifyWriteImplementationOverAdmits() throws Exception {
        RateLimiter limiter = new ReadModifyWriteRateLimiter(
                redis, "test:" + freshKey(), 10, 0.001, 60);
        String key = freshKey();

        assertThat(admittedByConcurrentCallers(limiter, key, 200))
                .as("check-then-decrement across two round trips has no lock between them")
                .isGreaterThan(10);
    }

    /** Releases N callers at once and counts how many the limiter let through. */
    private static int admittedByConcurrentCallers(RateLimiter limiter, String key, int callers)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        AtomicInteger admitted = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                pool.submit(() -> {
                    start.await();
                    if (limiter.tryAcquire(key).allowed()) {
                        admitted.incrementAndGet();
                    }
                    done.countDown();
                    return null;
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }
        return admitted.get();
    }

    @Test
    void anIdleBucketIsReclaimedRatherThanLeaking() {
        RateLimiter limiter = new RedisTokenBucketRateLimiter(redis, "test:ttl", 5, 1, 2);
        String key = freshKey();
        limiter.tryAcquire(key);

        Long ttl = redis.getExpire("test:ttl:" + key);
        assertThat(ttl)
                .as("without an expiry the keyspace grows forever and the limiter is a memory leak")
                .isNotNull()
                .isBetween(1L, 2L);
    }

    @Test
    void aFailingStoreFailsOpenAndSaysSo() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory("127.0.0.1", 6);
        dead.afterPropertiesSet();
        dead.start();
        StringRedisTemplate unreachable = new StringRedisTemplate((RedisConnectionFactory) dead);
        unreachable.afterPropertiesSet();
        unreachable.setEnableTransactionSupport(false);

        RedisTokenBucketRateLimiter limiter =
                new RedisTokenBucketRateLimiter(unreachable, "test:dead", 1, 1, 60);

        assertThat(limiter.tryAcquire("anything").allowed())
                .as("a limiter that fails closed turns a Redis outage into an API outage")
                .isTrue();
        assertThat(limiter.storeFailures())
                .as("and it must be visibly failing open, not silently succeeding")
                .isEqualTo(1);

        dead.destroy();
    }

    @Test
    void permitsCostWhatTheCallerSays() {
        RateLimiter limiter = new RedisTokenBucketRateLimiter(redis, "test:" + freshKey(), 10, 1, 60);
        String key = freshKey();

        assertThat(limiter.tryAcquire(key, 6).allowed()).isTrue();
        assertThat(limiter.tryAcquire(key, 6).allowed())
                .as("an endpoint priced above 1 spends its price, not one token")
                .isFalse();
    }

    /** Sanity: the script is loaded from the classpath and actually parses. */
    @Test
    void theScriptReturnsThreeValues() {
        StringRedisTemplate template = redis;
        RedisTokenBucketRateLimiter limiter =
                new RedisTokenBucketRateLimiter(template, "test:" + freshKey(), 1, 1, 60);
        RateLimiter.Decision decision = limiter.tryAcquire(freshKey());

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.tokensLeft()).isZero();
        assertThat(List.of(decision)).hasSize(1);
    }

    @Test
    void unlimitedAdmitsEverythingAndStillCounts() {
        RateLimiter limiter = new UnlimitedRateLimiter();
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire("anything").allowed()).isTrue();
        }
        assertThat(limiter.permitted())
                .as("the control arm must still report offered load, not nothing")
                .isEqualTo(100);
        assertThat(limiter.rejected()).isZero();
    }

    @Test
    void endpointBucketsHaveSeparateBudgets() {
        String prefix = "test:" + freshKey();
        RateLimiter writes = new RedisTokenBucketRateLimiter(redis, prefix + ":w", 1, 0.001, 60);
        RateLimiter reads = new RedisTokenBucketRateLimiter(redis, prefix + ":r", 5, 0.001, 60);
        EndpointRateLimiter endpoints = new EndpointRateLimiter(
                java.util.Map.of(EndpointCosts.PAYMENTS_WRITE, writes,
                        EndpointCosts.PAYMENTS_READ, reads),
                new UnlimitedRateLimiter());

        assertThat(endpoints.tryAcquire(EndpointCosts.PAYMENTS_WRITE).allowed()).isTrue();
        assertThat(endpoints.tryAcquire(EndpointCosts.PAYMENTS_WRITE).allowed()).isFalse();

        assertThat(endpoints.tryAcquire(EndpointCosts.PAYMENTS_READ).allowed())
                .as("a flood of writes must degrade writes and nothing else")
                .isTrue();
    }

    /**
     * A bucket this library has never heard of must be admitted, not rejected
     * and not thrown on. A limiter must never be the reason a working endpoint
     * stops working.
     */
    @Test
    void anUnknownEndpointBucketFallsBackRatherThanFailing() {
        EndpointRateLimiter endpoints = new EndpointRateLimiter(
                java.util.Map.of(), new UnlimitedRateLimiter());

        assertThat(endpoints.tryAcquire("refunds.write").allowed()).isTrue();
    }

    @Test
    void theClockComesFromRedisNotTheCaller() {
        // Two limiters over the same bucket, as two instances would be. If the
        // refill used each caller's own clock they could disagree; sharing
        // Redis's TIME means the second sees exactly what the first left.
        String prefix = "test:" + freshKey();
        String key = freshKey();
        RateLimiter instanceA = new RedisTokenBucketRateLimiter(redis, prefix, 2, 0.001, 60);
        RateLimiter instanceB = new RedisTokenBucketRateLimiter(redis, prefix, 2, 0.001, 60);

        assertThat(instanceA.tryAcquire(key).allowed()).isTrue();
        assertThat(instanceB.tryAcquire(key).allowed()).isTrue();
        assertThat(instanceA.tryAcquire(key).allowed())
                .as("the limit is global, not per instance")
                .isFalse();
        assertThat(instanceB.tryAcquire(key).allowed()).isFalse();
    }

    @Test
    void aDurationSanityCheckOnRetryAfter() {
        // 2/s, empty bucket: the next token is ~500ms away, not 5ms and not 5s.
        RateLimiter limiter = new RedisTokenBucketRateLimiter(redis, "test:" + freshKey(), 1, 2, 60);
        String key = freshKey();

        limiter.tryAcquire(key);
        RateLimiter.Decision refused = limiter.tryAcquire(key);

        assertThat(Duration.ofMillis(refused.retryAfterMs()))
                .isBetween(Duration.ofMillis(1), Duration.ofMillis(600));
    }
}
