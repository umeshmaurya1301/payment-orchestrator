package com.payorch.infra.resilience.lock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7h. The lock that is deliberately not a guarantee.
 *
 * <p>Most of these are about what happens when it <em>fails</em>, because that
 * is the half a distributed lock is usually written without: the acquire path is
 * three lines and the interesting behaviour is entirely in the release, the
 * outage and the overrun.
 */
class RedisLockTest {

    private static final Duration TTL = Duration.ofMinutes(1);

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedisLock lock;

    /** Every token this lock has written, in order. */
    private final List<String> tokensWritten = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);

        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    tokensWritten.add(invocation.getArgument(1));
                    return true;
                });
        // Released cleanly by default: the script found our token and deleted it.
        when(redis.execute(any(RedisScript.class), any(List.class), any()))
                .thenReturn(1L);

        lock = new RedisLock(redis, "payorch:lock");
    }

    @Test
    void theWinnerRunsTheWorkAndReleasesTheLock() {
        AtomicInteger runs = new AtomicInteger();

        Optional<String> result = lock.runIfAcquired("recon", TTL, () -> {
            runs.incrementAndGet();
            return "done";
        });

        assertThat(result).contains("done");
        assertThat(runs).hasValue(1);
        assertThat(lock.acquiredCount()).isEqualTo(1);

        // Released rather than left to expire, so the next tick is not blocked
        // by a lock nobody is using.
        verify(redis).execute(any(RedisScript.class),
                eq(List.of("payorch:lock:recon")), eq(tokensWritten.get(0)));
    }

    /** A loser does nothing at all - it does not run the work and does not wait. */
    @Test
    void aLoserSkipsTheRunEntirely() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        AtomicInteger runs = new AtomicInteger();

        Optional<String> result = lock.runIfAcquired("recon", TTL, () -> {
            runs.incrementAndGet();
            return "done";
        });

        assertThat(result).isEmpty();
        assertThat(runs).hasValue(0);
        assertThat(lock.contendedCount()).isEqualTo(1);
        // Nothing acquired means nothing to release. Calling the release script
        // here would delete the winner's lock.
        verify(redis, never()).execute(any(RedisScript.class), any(List.class), any());
    }

    /**
     * THE ASYMMETRY WITH THE RATE LIMITER, and it is deliberate.
     *
     * <p>{@code RedisTokenBucketRateLimiter} fails OPEN - a Redis outage must not
     * turn a degradation into an outage by refusing all traffic. A lock that
     * failed open would let EVERY instance run the job at once, which is exactly
     * what it exists to prevent, arriving precisely when the infrastructure is
     * already unwell.
     *
     * <p>Skipping is safe because these jobs are periodic and the next tick picks
     * up what this one missed. That is a property of the caller, not of the lock.
     */
    @Test
    void anUnreachableStoreSkipsTheRunRatherThanRunningItEverywhere() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("redis is down"));
        AtomicInteger runs = new AtomicInteger();

        Optional<String> result = lock.runIfAcquired("recon", TTL, () -> {
            runs.incrementAndGet();
            return "done";
        });

        assertThat(result).isEmpty();
        assertThat(runs)
                .as("failing open here would put every instance on the providers at once")
                .hasValue(0);
        assertThat(lock.storeFailureCount()).isEqualTo(1);
    }

    /**
     * Each acquisition gets its own token, not one per instance.
     *
     * <p>Two successive acquisitions by the same process must be
     * distinguishable, or the compare-and-delete release cannot tell them apart
     * - and telling them apart is the only reason the token exists.
     */
    @Test
    void everyAcquisitionUsesAFreshToken() {
        lock.runIfAcquired("recon", TTL, () -> "first");
        lock.runIfAcquired("recon", TTL, () -> "second");

        assertThat(tokensWritten).hasSize(2);
        assertThat(tokensWritten.get(0)).isNotEqualTo(tokensWritten.get(1));
    }

    /**
     * THE FAILURE THE WHOLE CLASS IS ABOUT.
     *
     * <p>The release script returning 0 means the key no longer held our token:
     * this run overran its TTL, the lock expired, and somebody else took it while
     * we were still working. Two instances have been doing the same job.
     *
     * <p>It is counted and logged rather than thrown, because the work is
     * already done and the jobs are idempotent - it is a cost problem and a
     * signal that the TTL is too short, not a correctness alarm.
     */
    @Test
    void anOverrunIsCountedRatherThanThrown() {
        when(redis.execute(any(RedisScript.class), any(List.class), any())).thenReturn(0L);

        Optional<String> result = lock.runIfAcquired("recon", TTL, () -> "done");

        assertThat(result)
                .as("the work finished; the caller must not be told it failed")
                .contains("done");
        assertThat(lock.lostBeforeReleaseCount()).isEqualTo(1);
    }

    /**
     * A release that cannot reach Redis must not fail the caller. The work is
     * done, and the lock expires on its own - which is what the TTL is for.
     */
    @Test
    void aFailedReleaseDoesNotFailAJobThatAlreadySucceeded() {
        when(redis.execute(any(RedisScript.class), any(List.class), any()))
                .thenThrow(new RedisConnectionFailureException("redis went away"));

        Optional<String> result = lock.runIfAcquired("recon", TTL, () -> "done");

        assertThat(result).contains("done");
        assertThat(lock.storeFailureCount()).isEqualTo(1);
    }

    /**
     * Work that throws still releases the lock. Without this, one failed run
     * blocks every subsequent one until the TTL expires - so a job that fails
     * fast and often would be locked out for far longer than it ran.
     */
    @Test
    void aFailedJobStillReleasesTheLock() {
        assertThatThrownBy(() -> lock.runIfAcquired("recon", TTL, () -> {
            throw new IllegalStateException("job blew up");
        })).isInstanceOf(IllegalStateException.class);

        verify(redis).execute(any(RedisScript.class), any(List.class), eq(tokensWritten.get(0)));
    }

    /** The exception is the caller's to handle - the lock does not swallow it. */
    @Test
    void aFailedJobPropagatesRatherThanBeingHiddenByTheLock() {
        assertThatThrownBy(() -> lock.runIfAcquired("recon", TTL, () -> {
            throw new IllegalStateException("job blew up");
        })).hasMessage("job blew up");
    }

    /** Different job names are different locks; one job must not block another. */
    @Test
    void locksAreScopedByName() {
        lock.runIfAcquired("recon", TTL, () -> "a");
        lock.runIfAcquired("sweep", TTL, () -> "b");

        verify(values).setIfAbsent(eq("payorch:lock:recon"), anyString(), any(Duration.class));
        verify(values).setIfAbsent(eq("payorch:lock:sweep"), anyString(), any(Duration.class));
    }

    /** The Runnable overload reports whether it ran, for a caller with no result. */
    @Test
    void theRunnableOverloadReportsWhetherItRan() {
        assertThat(lock.runIfAcquired("recon", TTL, () -> {
        })).isTrue();

        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertThat(lock.runIfAcquired("recon", TTL, () -> {
        })).isFalse();
    }

    /**
     * The TTL reaches Redis as part of the SET.
     *
     * <p>SETNX followed by EXPIRE is the version to avoid: a crash between the
     * two leaves a lock with no expiry at all, and that job never runs again
     * until somebody notices and deletes a key by hand.
     */
    @Test
    void theTtlIsSetAtomicallyWithTheKey() {
        lock.runIfAcquired("recon", Duration.ofSeconds(90), () -> "done");

        verify(values).setIfAbsent(eq("payorch:lock:recon"), anyString(),
                eq(Duration.ofSeconds(90)));
    }
}
