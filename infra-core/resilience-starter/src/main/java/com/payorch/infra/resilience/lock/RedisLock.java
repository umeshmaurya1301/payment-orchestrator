package com.payorch.infra.resilience.lock;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * Stops two instances doing the same scheduled work at the same time - usually.
 * Phase 7h.
 *
 * <h2>Read this before using it for anything</h2>
 *
 * <p><strong>This is an optimisation to avoid duplicate work. It is not a
 * correctness guarantee, and it must never be the only thing standing between
 * two workers and a double charge.</strong> Every job that takes this lock has
 * to be correct when the lock fails, because the lock will fail.
 *
 * <p>That is not pessimism about this implementation - it is true of every
 * TTL-based distributed lock, including Redlock, and the reasons are worth being
 * precise about because "we use a distributed lock" is one of the most
 * confidently wrong sentences in this business.
 *
 * <h3>Why a TTL lock cannot be a mutex</h3>
 *
 * <p>The lock is a key with an expiry. The holder is a process that Redis cannot
 * see, cannot pause, and cannot ask. So:
 *
 * <ol>
 *   <li>Worker A acquires with a 60-second TTL and starts the job.</li>
 *   <li>A stalls for 70 seconds. A stop-the-world GC pause, a descheduled
 *       container, a hypervisor migration, a disk that stopped answering -
 *       every one of these is ordinary.</li>
 *   <li>The TTL expires. Redis drops the key, correctly: from its side, the
 *       holder is gone.</li>
 *   <li>Worker B acquires the lock and starts the same job.</li>
 *   <li>A wakes up. It is still inside its critical section, still believes it
 *       holds the lock, and has no way to find out otherwise.</li>
 * </ol>
 *
 * <p>A and B are now running concurrently, and no amount of consensus between
 * Redis nodes changes that - the failure is between Redis and the holder, not
 * between the Redis nodes. This is the substance of Martin Kleppmann's critique
 * of Redlock: adding nodes makes the lock more available, not more correct, and
 * the only thing that would fix it is a <em>fencing token</em> - a
 * monotonically increasing number handed out with the lock, which the protected
 * RESOURCE checks and uses to reject a writer holding a stale one. That requires
 * the resource to participate, and a reconciliation job's resource is a payment
 * provider's API.
 *
 * <h3>What is done instead, and why it is enough here</h3>
 *
 * <p>The jobs this lock protects are <strong>idempotent</strong>. Phase 6e's
 * unique constraint means a ledger posting cannot be applied twice; phase 7a's
 * idempotency records mean a payment cannot be created twice; a reconciliation
 * that asks a provider what happened and writes the answer is safe to run twice
 * because the answer is the same both times.
 *
 * <p>So the lock buys exactly one thing: on the ordinary day, four instances do
 * not all wake up at 03:00 and hammer three providers with the same queries. It
 * is a cost control, not a safety control, and the moment it is treated as the
 * latter somebody will write a non-idempotent job behind it.
 *
 * <h3>No watchdog, deliberately</h3>
 *
 * <p>The usual next feature is a background thread that extends the TTL while
 * the job runs. It genuinely reduces the window - and it makes the lock
 * <em>look</em> like a mutex, which is worse than the window it closes. A
 * watchdog cannot renew during the stall that causes the problem, because the
 * stall stops the watchdog too. It converts a visible failure into a rare one,
 * and rare failures in payment systems are the expensive kind.
 *
 * <p>Instead the TTL is set generously and the job is written to survive
 * overlapping with itself.
 */
public class RedisLock {

    private static final Logger log = LoggerFactory.getLogger(RedisLock.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final RedisScript<Long> release;

    private final LongAdder acquired = new LongAdder();
    private final LongAdder contended = new LongAdder();
    private final LongAdder storeFailures = new LongAdder();
    private final LongAdder lostBeforeRelease = new LongAdder();

    public RedisLock(StringRedisTemplate redis, String keyPrefix) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;

        DefaultRedisScript<Long> loaded = new DefaultRedisScript<>();
        loaded.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("payorch/release-lock.lua")));
        loaded.setResultType(Long.class);
        this.release = loaded;
    }

    /**
     * Runs {@code work} if this instance wins the lock, and does nothing if it
     * does not.
     *
     * <h2>Fail CLOSED, unlike the rate limiter</h2>
     *
     * <p>{@code RedisTokenBucketRateLimiter} fails <em>open</em>: if Redis is
     * unreachable it admits the request, because refusing all traffic during a
     * Redis outage turns a degradation into an outage.
     *
     * <p>This does the opposite and the asymmetry is the point. A rate limiter
     * that fails open lets extra traffic through; a lock that failed open would
     * let <strong>every instance</strong> run the job simultaneously, which is
     * the exact scenario it exists to prevent, arriving precisely when the
     * infrastructure is already unwell. Skipping a scheduled run costs one
     * interval; running it four times over costs four times the provider load
     * during an incident.
     *
     * <p>A skipped run is safe here because these jobs are periodic - the next
     * tick picks up whatever this one missed. That is a property of the CALLER,
     * not of this class, and a caller whose work cannot be deferred should not
     * be using a lock that can decline.
     *
     * @return the result of {@code work}, or empty if the lock was held
     *         elsewhere or could not be reached
     */
    public <T> Optional<T> runIfAcquired(String name, Duration ttl, Supplier<T> work) {
        // A token unique to THIS acquisition, not to this instance. Two
        // successive acquisitions by the same process must not share a token, or
        // the compare-and-delete release cannot tell them apart - and telling
        // them apart is the whole reason the token exists.
        String token = UUID.randomUUID().toString();
        String key = keyPrefix + ":" + name;

        Boolean won;
        try {
            // SET key token NX PX ttl, in one round trip. SETNX followed by
            // EXPIRE is the version to avoid: a crash between the two leaves a
            // lock with no expiry, which is a job that never runs again until
            // somebody notices and deletes a key by hand.
            won = redis.opsForValue().setIfAbsent(key, token, ttl);
        } catch (RuntimeException e) {
            storeFailures.increment();
            log.warn("could not reach the lock store for '{}' - skipping this run "
                    + "rather than risking every instance running it at once: {}", name, e.toString());
            return Optional.empty();
        }

        if (!Boolean.TRUE.equals(won)) {
            contended.increment();
            log.debug("lock '{}' is held elsewhere - skipping this run", name);
            return Optional.empty();
        }

        acquired.increment();
        try {
            return Optional.ofNullable(work.get());
        } finally {
            releaseIfStillOurs(name, key, token);
        }
    }

    /**
     * Gives the lock back early, if it is still ours to give.
     *
     * <p>Released rather than left to expire, so the next scheduled tick is not
     * blocked by a lock nobody is using. The TTL is the safety net for a holder
     * that died, not the normal path.
     *
     * <p>A release that finds somebody else's token is <strong>the interesting
     * event in this class</strong>: it means this job overran its TTL and
     * another instance has been running the same work alongside it. Counted and
     * logged at WARN, because it is the observable symptom of the failure mode
     * the class javadoc describes - and the number to look at when deciding
     * whether the TTL is long enough.
     */
    private void releaseIfStillOurs(String name, String key, String token) {
        try {
            Long deleted = redis.execute(release, List.of(key), token);
            if (deleted == null || deleted == 0) {
                lostBeforeRelease.increment();
                log.warn("lock '{}' was no longer ours at release - this run overran its TTL "
                        + "and another instance may have been doing the same work alongside it. "
                        + "The job is idempotent, so this is a cost problem rather than a "
                        + "correctness one, but the TTL is too short.", name);
            }
        } catch (RuntimeException e) {
            // Not rethrown: the work is already done, and failing the caller now
            // would report a successful job as failed. The lock expires on its
            // own, which is what the TTL is for.
            storeFailures.increment();
            log.warn("could not release lock '{}'; it will expire on its own: {}",
                    name, e.toString());
        }
    }

    /** Runs where the result does not matter. */
    public boolean runIfAcquired(String name, Duration ttl, Runnable work) {
        return runIfAcquired(name, ttl, () -> {
            work.run();
            return Boolean.TRUE;
        }).isPresent();
    }

    public long acquiredCount() {
        return acquired.sum();
    }

    /** Runs skipped because another instance held the lock. Expected to be most of them. */
    public long contendedCount() {
        return contended.sum();
    }

    /** Runs skipped because the lock store could not be reached. */
    public long storeFailureCount() {
        return storeFailures.sum();
    }

    /**
     * Times a job overran its TTL and found the lock taken by somebody else.
     *
     * <p>The number that says the TTL is wrong. Not a correctness alarm - the
     * jobs behind this lock are idempotent - but every one of these is two
     * instances having done the same work, which is what the lock was for.
     */
    public long lostBeforeReleaseCount() {
        return lostBeforeRelease.sum();
    }
}
