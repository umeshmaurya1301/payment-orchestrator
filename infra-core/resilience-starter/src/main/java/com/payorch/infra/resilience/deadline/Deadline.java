package com.payorch.infra.resilience.deadline;

import java.time.Duration;

/**
 * How much time this request has left, anywhere in the call chain.
 *
 * <p>A budget, not a timeout. The distinction is the entire point of 3a.
 *
 * <p>With per-call timeouts of 3 s at each of four hops, a request can spend 12 s
 * being legitimately within every individual limit while the client gave up at
 * 5 s. Every hop reports success at doing its job and the whole thing is useless
 * work - and worse, work that is still holding connections and heap on four
 * services after the only party who cared has left.
 *
 * <p>A budget composes correctly because it is subtractive. The edge stamps
 * 30 s; by the time the connector is called, whatever the edge and the
 * orchestrator spent is already gone, and the connector can see it has 400 ms
 * left and decline to start a 3 s call.
 *
 * <p><strong>Elapsed time is measured with {@link System#nanoTime()}</strong>,
 * not the wall clock. Wall-clock time on a container can step backwards - NTP
 * correction, a host suspend - and a deadline that can go backwards is a
 * deadline that can silently never expire.
 *
 * @param budgetMs      total time this request was granted at this hop
 * @param startedAtNanos {@code System.nanoTime()} when this hop began
 */
public record Deadline(long budgetMs, long startedAtNanos) {

    public static Deadline of(long budgetMs) {
        return new Deadline(Math.max(budgetMs, 0), System.nanoTime());
    }

    public static Deadline of(Duration budget) {
        return of(budget.toMillis());
    }

    public long elapsedMs() {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    /** Never negative: an overrun reads as zero remaining, not as time owed. */
    public long remainingMs() {
        return Math.max(budgetMs - elapsedMs(), 0);
    }

    public Duration remaining() {
        return Duration.ofMillis(remainingMs());
    }

    public boolean isExpired() {
        return remainingMs() == 0;
    }

    /**
     * Whether there is enough time left to be worth starting something.
     *
     * <p>The floor exists because a call started with 5 ms remaining is
     * guaranteed to fail, and it is not free to fail: it opens a connection,
     * occupies a slot in the downstream's pool, and produces an error the
     * downstream has to log. Declining is strictly cheaper for everyone.
     */
    public boolean hasAtLeast(long millis) {
        return remainingMs() >= millis;
    }

    @Override
    public String toString() {
        return "Deadline[remaining=" + remainingMs() + "ms of " + budgetMs + "ms]";
    }
}
