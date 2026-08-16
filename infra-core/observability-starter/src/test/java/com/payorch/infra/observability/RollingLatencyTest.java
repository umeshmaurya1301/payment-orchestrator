package com.payorch.infra.observability;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic phase 5 will route on, pinned.
 *
 * <p>Worth testing properly rather than trusting, because the failure mode is
 * silent: a percentile that is quietly wrong still renders as a smooth line on a
 * graph, and a routing decision made from it looks like a routing bug rather
 * than a measurement bug.
 */
class RollingLatencyTest {

    private static final String KEY = RollingLatency.key("psp-b", "authorize");

    @Test
    void anEmptyWindowReportsMinusOneRatherThanZero() {
        RollingLatency latency = new RollingLatency(60);

        assertThat(latency.p99Ms(KEY))
                .as("phase 5 must tell 'very fast' from 'nobody has called it' - "
                        + "reporting a silent provider as 0ms would send it all the traffic")
                .isEqualTo(-1);
        assertThat(latency.count(KEY)).isZero();
    }

    @Test
    void thePercentileIsTheBucketTheRankFallsIn() {
        RollingLatency latency = new RollingLatency(60);
        // 99 fast calls and one slow one: the P99 must be the slow one.
        for (int i = 0; i < 99; i++) {
            latency.record(KEY, 10);
        }
        latency.record(KEY, 3_000);

        assertThat(latency.count(KEY)).isEqualTo(100);
        assertThat(latency.p50Ms(KEY)).isEqualTo(10);
        assertThat(latency.p99Ms(KEY))
                .as("the 99th of 100 samples is still the fast one; the 100th is the outlier")
                .isEqualTo(10);

        // One more slow call moves the 99th rank onto the slow side.
        latency.record(KEY, 3_000);
        assertThat(latency.p99Ms(KEY)).isEqualTo(3_000);
    }

    /**
     * Bucket boundaries are the accuracy limit, and the direction of the error
     * matters: a percentile is reported as the bucket's UPPER bound, so the
     * number is never optimistic. Routing on a value that under-reports a slow
     * provider is the failure worth avoiding.
     */
    @Test
    void aLatencyIsReportedAtItsBucketsUpperBound() {
        RollingLatency latency = new RollingLatency(60);
        latency.record(KEY, 2_180);

        assertThat(latency.p99Ms(KEY))
                .as("2,180ms falls in the 2,000-2,500 bucket and is reported as 2,500 - "
                        + "never as 2,000, so the number is never optimistic")
                .isEqualTo(2_500);
    }

    @Test
    void providersAndOperationsDoNotShareAWindow() {
        RollingLatency latency = new RollingLatency(60);
        String fast = RollingLatency.key("psp-a", "authorize");
        String slow = RollingLatency.key("psp-b", "authorize");
        String otherOperation = RollingLatency.key("psp-a", "status");

        for (int i = 0; i < 50; i++) {
            latency.record(fast, 200);
            latency.record(slow, 2_500);
            latency.record(otherOperation, 5);
        }

        assertThat(latency.p99Ms(fast)).isEqualTo(200);
        assertThat(latency.p99Ms(slow)).isEqualTo(2_500);
        assertThat(latency.p99Ms(otherOperation))
                .as("authorize and status fail and slow independently - 3c made the same "
                        + "split for the breaker and this has to match it")
                .isEqualTo(5);
    }

    /**
     * The property the whole class exists for: buckets add, so two sources can
     * be merged and the percentile computed from the total. Percentiles do not
     * add, which is why this is not two Timers and a mean.
     */
    @Test
    void samplesFromSeparateSecondsMergeIntoOneDistribution() throws Exception {
        RollingLatency latency = new RollingLatency(60);

        for (int i = 0; i < 50; i++) {
            latency.record(KEY, 10);
        }
        Thread.sleep(1_100);          // land the next batch in a different slot
        for (int i = 0; i < 50; i++) {
            latency.record(KEY, 1_000);
        }

        assertThat(latency.count(KEY))
                .as("both seconds are inside the window, so both are counted")
                .isEqualTo(100);
        assertThat(latency.p50Ms(KEY))
                .as("the median of 50 fast and 50 slow sits at the boundary, not at a mean")
                .isEqualTo(10);
        assertThat(latency.p99Ms(KEY)).isEqualTo(1_000);
    }

    /**
     * A slot older than the window must be dropped rather than accumulated, and
     * dropped on read rather than by a sweeper thread. A background cleaner is a
     * moving part that can stop moving, and its failure - stale samples silently
     * inflating a percentile - is exactly what a routing input must not have.
     */
    @Test
    void samplesOlderThanTheWindowFallOutOfIt() throws Exception {
        RollingLatency latency = new RollingLatency(2);

        for (int i = 0; i < 100; i++) {
            latency.record(KEY, 5_000);
        }
        assertThat(latency.count(KEY)).isEqualTo(100);

        Thread.sleep(2_600);

        assertThat(latency.count(KEY))
                .as("a two-second window holds nothing from three seconds ago")
                .isZero();
        assertThat(latency.p99Ms(KEY))
                .as("and an expired window is silent, not fast")
                .isEqualTo(-1);
    }

    /**
     * Recording sits on the hot path of every provider call. It has to be
     * lock-free and it has to not lose counts - a histogram that drops samples
     * under load under-reports exactly when the numbers matter.
     */
    @Test
    void concurrentRecordingLosesNothing() throws Exception {
        RollingLatency latency = new RollingLatency(60);
        int threads = 64;
        int perThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        latency.record(KEY, 42);
                    }
                    done.countDown();
                    return null;
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(latency.count(KEY)).isEqualTo((long) threads * perThread);
        assertThat(latency.p99Ms(KEY)).isEqualTo(50);
    }

    @Test
    void anythingBeyondTheLastBoundaryLandsInTheOverflowBucket() {
        RollingLatency latency = new RollingLatency(60);
        latency.record(KEY, 600_000);

        assertThat(latency.p99Ms(KEY))
                .as("a ten-minute call is off the scale, and 3a's budget should have "
                        + "abandoned it long before - but it must still be counted")
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void theKeyIsProviderAndOperation() {
        assertThat(RollingLatency.key("psp-a", "authorize")).isEqualTo("psp-a:authorize");
    }
}
