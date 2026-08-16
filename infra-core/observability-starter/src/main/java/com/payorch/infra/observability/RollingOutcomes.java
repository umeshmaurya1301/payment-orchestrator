package com.payorch.infra.observability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Rolling success rate per provider, in the same one-second slots
 * {@link RollingLatency} uses.
 *
 * <p>Phase 5 needs four signals to score a provider's health, and this is the
 * one that did not already exist. The obvious substitute is the circuit
 * breaker's own failure rate, and experiment 03 is the reason it cannot be used:
 * resilience4j resets that counter on every state transition, so a flapping
 * breaker reports a failure rate of 0 while refusing thousands of calls. A
 * routing input that reads healthiest exactly when a provider is at its worst
 * would be worse than having no input at all.
 *
 * <p>It is also a different question from the breaker's. The breaker asks
 * "should I stop calling this provider" and answers with a latch; routing asks
 * "how much of the traffic does this provider deserve" and needs a number that
 * moves smoothly. Deriving the second from the first is what makes systems
 * oscillate.
 *
 * <h2>Why time-based slots and not a ratio counter</h2>
 *
 * <p>Same reasoning as {@link RollingLatency} and 3c's breaker: a count-based
 * window means "the last 100 calls", which is six seconds at 16 rps and half an
 * hour at one call every 20 seconds. This system's traffic spans two orders of
 * magnitude between a smoke script and a ramp, so the window has to be
 * expressed in the unit an operator reasons in.
 *
 * <h2>The stale-provider trap</h2>
 *
 * <p>{@link #samples} exists so callers can tell "100% success" from "no calls
 * at all", which arrive here as the same success rate of 1.0 and mean opposite
 * things. A provider receiving no traffic generates no signal, so a health score
 * that trusts an empty window will either keep a dead provider at the top of the
 * list forever or, worse, park a recovered one at the bottom of it with no way
 * back. The phase-5 plan lists this as its own trap; the scorer handles it by
 * decaying toward neutral, and it can only do that because this class reports
 * how much evidence it has.
 */
public class RollingOutcomes {

    private final int windowSeconds;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RollingOutcomes(int windowSeconds) {
        this.windowSeconds = Math.max(1, windowSeconds);
    }

    public void record(String key, boolean success) {
        windows.computeIfAbsent(key, k -> new Window(windowSeconds)).record(success);
    }

    /**
     * Fraction of calls in the window that succeeded, or {@code -1} when the
     * window is empty.
     *
     * <p>-1 rather than 0.0 or 1.0, and for the same reason
     * {@link RollingLatency#p99Ms} returns -1: "no data" is not a value on the
     * same scale as the thing being measured, and encoding it as one produces a
     * provider that looks perfect or broken purely for want of traffic.
     */
    public double successRate(String key) {
        Window window = windows.get(key);
        return window == null ? -1 : window.successRate();
    }

    /** Calls currently inside the window. Zero means the rate is meaningless. */
    public long samples(String key) {
        Window window = windows.get(key);
        return window == null ? 0 : window.samples();
    }

    /**
     * A ring of per-second slots holding successes and failures.
     *
     * <p>Two parallel arrays rather than an array of pairs: this is incremented
     * on the hot path of every provider call, and two independent atomic
     * increments are cheaper and less contended than one compare-and-set over a
     * composite.
     */
    private static final class Window {

        private final int size;
        private final AtomicLongArray ok;
        private final AtomicLongArray failed;
        private final AtomicLongArray slotSecond;

        Window(int windowSeconds) {
            this.size = windowSeconds;
            this.ok = new AtomicLongArray(size);
            this.failed = new AtomicLongArray(size);
            this.slotSecond = new AtomicLongArray(size);
        }

        void record(boolean success) {
            long second = System.currentTimeMillis() / 1000;
            int slot = (int) Math.floorMod(second, size);
            reclaim(slot, second);
            (success ? ok : failed).incrementAndGet(slot);
        }

        /**
         * Clears a slot the first time it is used for a new second.
         *
         * <p>The compareAndSet is what makes this safe under concurrency: many
         * threads may find the slot stale at once, and exactly one wins the
         * right to zero it. The losers proceed to increment, which is correct -
         * they are recording into the second the winner just opened.
         */
        private void reclaim(int slot, long second) {
            long stamped = slotSecond.get(slot);
            if (stamped != second && slotSecond.compareAndSet(slot, stamped, second)) {
                ok.set(slot, 0);
                failed.set(slot, 0);
            }
        }

        private long[] totals() {
            long oldest = System.currentTimeMillis() / 1000 - size + 1;
            long good = 0;
            long bad = 0;
            for (int i = 0; i < size; i++) {
                // Slots whose stamp is older than the window are leftovers from
                // a previous lap of the ring and must not be counted.
                if (slotSecond.get(i) >= oldest) {
                    good += ok.get(i);
                    bad += failed.get(i);
                }
            }
            return new long[]{good, bad};
        }

        double successRate() {
            long[] t = totals();
            long total = t[0] + t[1];
            return total == 0 ? -1 : (double) t[0] / total;
        }

        long samples() {
            long[] t = totals();
            return t[0] + t[1];
        }
    }
}
