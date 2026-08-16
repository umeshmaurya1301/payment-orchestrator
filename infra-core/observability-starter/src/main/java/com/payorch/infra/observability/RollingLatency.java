package com.payorch.infra.observability;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Rolling latency per provider and operation, as <strong>histogram buckets</strong>.
 *
 * <p>Phase 5 routes on this. That is the reason it is a component with a method
 * rather than a chart with a query: a routing decision that has to call a
 * dashboard API is a routing decision that stops working when the dashboard
 * does, and it puts a monitoring system on the payment path.
 *
 * <h2>Why buckets, and why this is not {@code Timer.percentile()}</h2>
 *
 * <p>Micrometer will happily compute a percentile per instance. Those numbers
 * cannot be combined, because <strong>percentiles do not aggregate</strong>. If
 * instance A reports P99 = 100 ms and instance B reports P99 = 200 ms, the fleet
 * P99 is not 150 ms - it is somewhere between 100 and 200, and where depends on
 * how the requests were distributed across the two. The mean of percentiles is a
 * number with no meaning, and it is the kind of wrong that looks plausible on a
 * graph.
 *
 * <p>What does combine is counts. Two instances that each export "how many calls
 * fell in the 100-120 ms bucket" can have their buckets added, and the
 * percentile computed from the merged histogram is correct. So this class stores
 * counts per bucket and computes the percentile at read time, which is also what
 * {@code publishPercentileHistogram} does on the wire.
 *
 * <p>The cost is real and worth stating: accuracy is bounded by bucket width.
 * A P99 that lands in the 2,000-2,500 ms bucket is reported as 2,500 ms, not as
 * 2,180 ms. For a routing input that is not merely acceptable, it is desirable -
 * routing on a number that jitters by single milliseconds would flap.
 *
 * <h2>Rolling, in one-second slots</h2>
 *
 * <p>A ring of per-second slots, overwritten as time advances, so "the last 60
 * seconds" means the same thing at 2 rps and at 500 rps. A count-based window
 * would mean 0.1 s of memory at 500 rps and 50 s at 2 rps - the same reasoning
 * that made 3c's circuit breaker time-based rather than count-based, and for the
 * same reason: this system's traffic varies by two orders of magnitude between a
 * smoke script and a ramp.
 *
 * <p>Lock-free. Recording is one array increment on the hot path of every
 * provider call, and a lock there would make the observability the bottleneck it
 * is supposed to be measuring.
 */
public class RollingLatency {

    /**
     * Bucket upper bounds in milliseconds, roughly geometric.
     *
     * <p>Chosen against what this system actually does rather than as a generic
     * ladder: dense from 10-500 ms where provider A and C live, coarser through
     * the seconds where B lives and where the deadline budget is, and a final
     * bucket at 60 s that catches anything the 3a budget should already have
     * abandoned. A histogram whose buckets do not match the distribution reports
     * everything in one bucket and answers nothing.
     */
    static final long[] BUCKET_UPPER_BOUNDS_MS = {
            1, 2, 5, 10, 20, 30, 50, 75, 100, 150, 200, 300, 400, 500, 750,
            1_000, 1_500, 2_000, 2_500, 3_000, 4_000, 5_000, 7_500,
            10_000, 15_000, 20_000, 30_000, 45_000, 60_000, Long.MAX_VALUE
    };

    private final int windowSeconds;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RollingLatency(int windowSeconds) {
        this.windowSeconds = Math.max(1, windowSeconds);
    }

    /** @param key {@code pspId:operation} - see {@link #key} */
    public void record(String key, long latencyMs) {
        windows.computeIfAbsent(key, k -> new Window(windowSeconds)).record(latencyMs);
    }

    /**
     * @param quantile 0.99 for P99
     * @return the upper bound of the bucket the quantile falls in, or -1 when
     *         the window holds no samples. <strong>-1 rather than 0</strong>:
     *         phase 5 must be able to tell "this provider is very fast" from
     *         "nobody has called this provider recently", and a routing input
     *         that reports a silent provider as the fastest one would send it
     *         all the traffic.
     */
    public long percentileMs(String key, double quantile) {
        Window window = windows.get(key);
        return window == null ? -1 : window.percentileMs(quantile);
    }

    public long p99Ms(String key) {
        return percentileMs(key, 0.99);
    }

    public long p50Ms(String key) {
        return percentileMs(key, 0.50);
    }

    /** Samples currently in the window. The denominator behind any percentile above. */
    public long count(String key) {
        Window window = windows.get(key);
        return window == null ? 0 : window.count();
    }

    public Set<String> keys() {
        return Set.copyOf(windows.keySet());
    }

    public static String key(String pspId, String operation) {
        return pspId + ":" + operation;
    }

    /**
     * A ring of per-second bucket arrays.
     *
     * <p>Each slot carries the epoch second it holds, so a slot that has fallen
     * out of the window is recognised on read and skipped rather than needing a
     * sweeper thread to clear it. A background cleaner would be a second moving
     * part that can stop moving without anyone noticing, and its failure mode -
     * stale samples silently inflating a percentile - is exactly the failure a
     * routing input must not have.
     */
    private static final class Window {

        private final int slots;
        private final AtomicLongArray counts;
        private final AtomicLongArray slotSecond;

        Window(int slots) {
            this.slots = slots;
            this.counts = new AtomicLongArray(slots * BUCKET_UPPER_BOUNDS_MS.length);
            this.slotSecond = new AtomicLongArray(slots);
            for (int i = 0; i < slots; i++) {
                slotSecond.set(i, -1);
            }
        }

        void record(long latencyMs) {
            long second = System.currentTimeMillis() / 1000;
            int slot = (int) Math.floorMod(second, slots);

            // Claim the slot for this second if it belongs to an older one.
            // A losing thread in this race simply finds the value already set.
            long held = slotSecond.get(slot);
            if (held != second && slotSecond.compareAndSet(slot, held, second)) {
                int base = slot * BUCKET_UPPER_BOUNDS_MS.length;
                for (int i = 0; i < BUCKET_UPPER_BOUNDS_MS.length; i++) {
                    counts.set(base + i, 0);
                }
            }

            counts.incrementAndGet(slot * BUCKET_UPPER_BOUNDS_MS.length + bucketFor(latencyMs));
        }

        long count() {
            long[] merged = merge();
            long total = 0;
            for (long bucket : merged) {
                total += bucket;
            }
            return total;
        }

        long percentileMs(double quantile) {
            long[] merged = merge();
            long total = 0;
            for (long bucket : merged) {
                total += bucket;
            }
            if (total == 0) {
                return -1;
            }

            // The rank the quantile refers to, then walk the cumulative counts.
            // Ceiling rather than rounding: P99 of 100 samples must be the 99th,
            // not the 99th-or-98th depending on floating point.
            long rank = (long) Math.ceil(quantile * total);
            long cumulative = 0;
            for (int i = 0; i < merged.length; i++) {
                cumulative += merged[i];
                if (cumulative >= rank) {
                    return BUCKET_UPPER_BOUNDS_MS[i];
                }
            }
            return BUCKET_UPPER_BOUNDS_MS[BUCKET_UPPER_BOUNDS_MS.length - 1];
        }

        /** Adds up every slot still inside the window. This is the merge the class exists for. */
        private long[] merge() {
            long oldestValidSecond = (System.currentTimeMillis() / 1000) - slots + 1;
            long[] merged = new long[BUCKET_UPPER_BOUNDS_MS.length];
            for (int slot = 0; slot < slots; slot++) {
                if (slotSecond.get(slot) < oldestValidSecond) {
                    continue;
                }
                int base = slot * BUCKET_UPPER_BOUNDS_MS.length;
                for (int i = 0; i < merged.length; i++) {
                    merged[i] += counts.get(base + i);
                }
            }
            return merged;
        }
    }

    static int bucketFor(long latencyMs) {
        for (int i = 0; i < BUCKET_UPPER_BOUNDS_MS.length; i++) {
            if (latencyMs <= BUCKET_UPPER_BOUNDS_MS[i]) {
                return i;
            }
        }
        return BUCKET_UPPER_BOUNDS_MS.length - 1;
    }
}
