package com.payorch.infra.resilience.ratelimit;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * The real one: a token bucket evaluated atomically by
 * {@code payorch/token-bucket.lua} inside Redis.
 *
 * <p>Distributed because the buckets live in Redis rather than in a JVM. A
 * per-instance limiter is not a per-merchant limit, it is a per-merchant
 * <em>per instance</em> limit, so the effective ceiling multiplies by the
 * replica count and changes every time the deployment scales. That is a limit
 * nobody can reason about and it is silently wrong rather than loudly wrong.
 *
 * <p>Atomic because {@code GET}, decide, {@code SET} is three round trips with
 * no lock between them - see {@link ReadModifyWriteRateLimiter}, which exists
 * only so the difference can be measured rather than asserted.
 *
 * <h2>What happens when Redis is down</h2>
 *
 * <p>Fail <strong>open</strong>, deliberately, and it is a judgement rather than
 * an oversight. A limiter that fails closed converts "the rate limiter's data
 * store is unavailable" into "the payment API is down", which trades a
 * degradation for an outage - and the thing being protected against (one noisy
 * merchant) is far less likely than the thing being caused (everyone refused).
 *
 * <p>It is the wrong default for a correctness control and the right one for a
 * fairness control. The idempotency markers that phase 7 puts in this same Redis
 * must fail closed, because admitting a duplicate payment is not a degradation.
 * Same store, opposite policy, and the reason is what the data protects.
 */
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;
    private final int capacity;
    private final double refillPerSec;
    private final int ttlSeconds;
    private final String keyPrefix;

    private final LongAdder permitted = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder storeFailures = new LongAdder();

    /** Per-key overrides, populated from {@code psp_config} in 3f. */
    private final java.util.Map<String, Bucket> perKey = new java.util.concurrent.ConcurrentHashMap<>();

    private record Bucket(int capacity, double refillPerSec) {
    }

    /**
     * Sets one key's rate while the system is running - 3f.
     *
     * <p>The egress layer needs this more obviously than any other: the number
     * being enforced is <em>the provider's contracted TPS</em>, so a single value
     * shared across providers is wrong for all but one of them. 3e shipped with
     * exactly that, and the writeup says so.
     *
     * <p>Cheap to change because the script takes capacity and rate as arguments
     * rather than baking them into the stored bucket. The tokens already in
     * Redis stay where they are and the next call simply refills against the new
     * rate - no flush, no reset, and no window during which the limit is
     * unenforced.
     */
    public void configure(String key, int capacity, double refillPerSec) {
        perKey.put(key, new Bucket(capacity, refillPerSec));
    }

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis, String keyPrefix,
                                       int capacity, double refillPerSec, int ttlSeconds) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.capacity = capacity;
        this.refillPerSec = refillPerSec;
        this.ttlSeconds = ttlSeconds;

        DefaultRedisScript<List> loaded = new DefaultRedisScript<>();
        loaded.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("payorch/token-bucket.lua")));
        loaded.setResultType(List.class);
        this.script = loaded;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Decision tryAcquire(String key, int permits) {
        Bucket bucket = perKey.getOrDefault(key, new Bucket(capacity, refillPerSec));

        List<Long> result;
        try {
            // Spring sends EVALSHA first and falls back to EVAL on NOSCRIPT, so
            // the script body crosses the wire once per Redis process rather
            // than once per request.
            result = redis.execute(script, List.of(keyPrefix + ":" + key),
                    Integer.toString(bucket.capacity()),
                    Double.toString(bucket.refillPerSec()),
                    Integer.toString(permits),
                    Integer.toString(ttlSeconds));
        } catch (RuntimeException e) {
            // Fail open. See the class javadoc - this is the line that decides
            // whether a Redis outage is a degradation or an outage.
            storeFailures.increment();
            permitted.increment();
            return Decision.allowed(-1);
        }

        if (result == null || result.size() < 3) {
            storeFailures.increment();
            permitted.increment();
            return Decision.allowed(-1);
        }

        boolean allowed = result.get(0) == 1L;
        if (allowed) {
            permitted.increment();
            return Decision.allowed(result.get(1));
        }
        rejected.increment();
        return Decision.rejected(result.get(1), result.get(2));
    }

    @Override
    public String kind() {
        return "atomic-lua";
    }

    @Override
    public long permitted() {
        return permitted.sum();
    }

    @Override
    public long rejected() {
        return rejected.sum();
    }

    /**
     * Requests admitted because Redis could not answer.
     *
     * <p>Exposed as its own metric rather than folded into {@code permitted},
     * because "the limiter is working" and "the limiter is failing open" look
     * identical from a success rate and must not.
     */
    public long storeFailures() {
        return storeFailures.sum();
    }
}
