package com.payorch.infra.resilience.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The same token bucket, done the obvious way: read it, decide in Java, write it
 * back. <strong>It over-admits, and it is here to be measured doing so.</strong>
 *
 * <p>This is not a strawman. It is what a rate limiter looks like when written
 * without thinking about the gap between the read and the write, and it passes
 * every test that exercises it one request at a time. The phase-3 plan asserts
 * that "check-then-decrement across two round trips is not atomic and
 * over-admits under concurrency"; the project's rule is that a claim in a
 * document is worth less than a number, so the claim gets an implementation and
 * an arm in the experiment.
 *
 * <h2>The race, concretely</h2>
 *
 * <pre>
 *   bucket = 1 token, two callers arrive together
 *
 *   caller A  HGET  -> 1        caller B  HGET  -> 1
 *   caller A  1 >= 1, allow     caller B  1 >= 1, allow
 *   caller A  HSET  -> 0        caller B  HSET  -> 0
 *
 *   two admitted from one token, and the bucket does not know
 * </pre>
 *
 * <p>Note the shape of the failure: the excess is not a fixed error, it scales
 * with how many callers overlap the window between read and write. So the
 * limiter is accurate when it is not needed and worst when it is - the same
 * signature as a check-then-act bug anywhere else, and the reason "it worked in
 * testing" is the expected observation rather than a surprising one.
 *
 * <p>Selectable at runtime via {@code RATELIMIT_KIND=read-modify-write}. It is
 * never the default and there is no configuration in which it is correct.
 */
public class ReadModifyWriteRateLimiter implements RateLimiter {

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final int capacity;
    private final double refillPerSec;
    private final int ttlSeconds;

    private final LongAdder permitted = new LongAdder();
    private final LongAdder rejected = new LongAdder();

    public ReadModifyWriteRateLimiter(StringRedisTemplate redis, String keyPrefix,
                                      int capacity, double refillPerSec, int ttlSeconds) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.capacity = capacity;
        this.refillPerSec = refillPerSec;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public Decision tryAcquire(String key, int permits) {
        String bucketKey = keyPrefix + ":" + key;
        long nowMs = System.currentTimeMillis();

        double tokens;
        long ts;
        try {
            // Round trip one.
            Map<Object, Object> stored = redis.opsForHash().entries(bucketKey);
            Object storedTokens = stored.get("tokens");
            Object storedTs = stored.get("ts");
            if (storedTokens == null || storedTs == null) {
                tokens = capacity;
                ts = nowMs;
            } else {
                tokens = Double.parseDouble(storedTokens.toString());
                ts = Long.parseLong(storedTs.toString());
            }
        } catch (RuntimeException e) {
            permitted.increment();
            return Decision.allowed(-1);
        }

        // ---- the gap ----------------------------------------------------
        // Every other caller that read this bucket before the write below sees
        // the value this decision is about to invalidate. Nothing here is
        // wrong in isolation; the wrongness is entirely in the interleaving.
        long elapsedMs = Math.max(0, nowMs - ts);
        tokens = Math.min(capacity, tokens + (elapsedMs * refillPerSec / 1000.0));

        boolean allowed = tokens >= permits;
        long retryAfterMs = 0;
        if (allowed) {
            tokens -= permits;
        } else {
            retryAfterMs = (long) Math.ceil(((permits - tokens) * 1000.0) / refillPerSec);
        }

        try {
            // Round trip two. Last writer wins, and the losers' decrements are
            // simply gone.
            redis.opsForHash().putAll(bucketKey,
                    Map.of("tokens", Double.toString(tokens), "ts", Long.toString(nowMs)));
            redis.expire(bucketKey, Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException e) {
            // Ignored on purpose: this implementation exists to be wrong in one
            // specific, measurable way, and adding error handling it does not
            // have in the wild would blur what is being measured.
        }

        if (allowed) {
            permitted.increment();
            return Decision.allowed((long) tokens);
        }
        rejected.increment();
        return Decision.rejected((long) tokens, retryAfterMs);
    }

    @Override
    public String kind() {
        return "read-modify-write";
    }

    @Override
    public long permitted() {
        return permitted.sum();
    }

    @Override
    public long rejected() {
        return rejected.sum();
    }
}
