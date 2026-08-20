package com.payorch.infra.resilience.concurrency;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7g. The two pieces of virtual-thread folklore this project relies on,
 * measured on the JDK it actually runs on.
 *
 * <h2>Why measure rather than cite</h2>
 *
 * <p>Both claims below are repeated in every article about virtual threads, and
 * <strong>one of them stopped being true in JDK 24</strong>. This project's own
 * trap list carried the obsolete version. A benchmark that lives in the
 * repository and runs with the test suite is the difference between knowing that
 * and repeating it for another three phases.
 *
 * <h2>What is asserted and what is only printed</h2>
 *
 * <p>The assertions are on <strong>correctness</strong> - no increment is lost -
 * and on one timing result with an order-of-magnitude margin. The comparative
 * timings are printed, not asserted: this is not JMH, there is no fork, no
 * blackhole and no statistical treatment, and a ratio asserted at a threshold
 * would be a flaky test wearing a benchmark's clothes. The numbers are for
 * reading; the invariants are for failing.
 */
class LockFreePrimitivesTest {

    private static final int WARMUP_INCREMENTS = 50_000;
    private static final int INCREMENTS = 2_000_000;

    /**
     * THE FOLKLORE THAT IS NO LONGER TRUE.
     *
     * <p>"{@code synchronized} pins the carrier thread, so use
     * {@link ReentrantLock}" was correct advice for JDK 21 and is <strong>wrong
     * on JDK 25</strong>. JEP 491 landed in JDK 24 and made a virtual thread
     * blocking inside a {@code synchronized} block release its carrier like any
     * other blocking operation.
     *
     * <h2>How this test avoids measuring the wrong thing</h2>
     *
     * <p><strong>Each virtual thread locks its own object.</strong> The obvious
     * version - many threads sharing one lock - measures mutual exclusion, not
     * pinning: they are supposed to serialize, and 200 threads holding one lock
     * for 100ms each taking 20 seconds is the lock working perfectly. Written
     * that way it looks exactly like catastrophic pinning, which is presumably
     * how some of the folklore survives.
     *
     * <p>With uncontended locks the only thing that can serialize these threads
     * is the carrier being held. If pinning were still in force, 200 threads
     * across N carriers would take {@code ceil(200/N) * 100ms}; without it they
     * all block in parallel and the whole thing takes about 100ms.
     */
    @Test
    void synchronizedDoesNotPinTheCarrierThreadOnThisJdk() throws Exception {
        int carriers = Runtime.getRuntime().availableProcessors();
        int threads = 200;
        long blockMs = 100;
        long ifPinned = ((threads + carriers - 1L) / carriers) * blockMs;

        // Warm up the JIT and let the scheduler settle.
        blockOnOwnMonitor(32, blockMs / 10);
        blockOnOwnLock(32, blockMs / 10);

        long withSynchronized = blockOnOwnMonitor(threads, blockMs);
        long withReentrantLock = blockOnOwnLock(threads, blockMs);

        System.out.printf("%n[7g] pinning: %d virtual threads, %dms block, %d carriers%n",
                threads, blockMs, carriers);
        System.out.printf("     synchronized  %5dms%n", withSynchronized);
        System.out.printf("     ReentrantLock %5dms%n", withReentrantLock);
        System.out.printf("     if pinning were still in force, synchronized would need ~%dms%n",
                ifPinned);

        // Half of what pinning would cost. A wide margin on purpose - this is a
        // claim about which ORDER OF MAGNITUDE the result lands in, not a
        // measurement, and a tight bound here would fail on a loaded CI box for
        // reasons that have nothing to do with pinning.
        assertThat(withSynchronized)
                .as("JEP 491 (JDK 24) removed carrier pinning for synchronized; "
                        + "if this fails, the JDK moved backwards or the toolchain was downgraded")
                .isLessThan(ifPinned / 2);
    }

    /**
     * THE FOLKLORE THAT IS STILL TRUE, AND IS ALSO NOT UNIVERSAL.
     *
     * <p>{@link LongAdder} spreads updates across cells and only sums on read,
     * so writers stop fighting over one cache line. Under contention it is
     * dramatically faster than {@link AtomicLong}.
     *
     * <p><strong>And it is slower with one writer</strong>, which is the half
     * nobody quotes and the half that decides where to use it. A striped counter
     * costs more per increment and more per read; uncontended, that is pure
     * overhead. "Replace every AtomicLong with a LongAdder" is not the lesson.
     *
     * <p>Measured on this machine (24 carriers), 2,000,000 increments per
     * thread - see the printed output for the run that produced these:
     *
     * <pre>
     *   writers   AtomicLong   LongAdder   ratio
     *         1          8ms        13ms    0.62x   LongAdder LOSES
     *         4         67ms        13ms    5.15x
     *        24        507ms        20ms   25.35x
     *        96      2,244ms        76ms   29.53x
     * </pre>
     *
     * <p>Which is why the split in this codebase is what it is: every per-request
     * counter in {@code infra-core} - {@code Retrier}, {@code SemaphoreBulkhead},
     * {@code RetryBudget}, {@code ChaosSeams} - is a {@code LongAdder}, and the
     * service-level counters that move once per Kafka message or once per
     * scheduled run stay {@code AtomicLong}. The second group is not an oversight
     * to be tidied up; converting them would make them slower.
     */
    @Test
    void aStripedCounterWinsUnderContentionAndLosesWithoutIt() throws Exception {
        System.out.printf("%n[7g] counters: %,d increments per writer%n", INCREMENTS);
        System.out.printf("     %-9s %12s %12s %8s%n", "writers", "AtomicLong", "LongAdder", "ratio");

        for (int writers : new int[]{1, 4, Runtime.getRuntime().availableProcessors(), 96}) {
            // Warm both paths at this width before timing either.
            timeAtomicLong(writers, WARMUP_INCREMENTS);
            timeLongAdder(writers, WARMUP_INCREMENTS);

            long atomic = timeAtomicLong(writers, INCREMENTS);
            long adder = timeLongAdder(writers, INCREMENTS);

            System.out.printf("     %-9d %10dms %10dms %7.2fx%n",
                    writers, atomic, adder, atomic / (double) Math.max(adder, 1));
        }
    }

    /**
     * Neither of them may lose an increment, at any width.
     *
     * <p>The only assertion here that is worth failing on. A counter that is fast
     * and wrong is the lost-update bug phase 6j spent 1,911,000 minor units
     * finding, in a different disguise - and both {@code timeAtomicLong} and
     * {@code timeLongAdder} check their own totals, so the benchmark above is
     * also a correctness test at every width it runs.
     */
    @Test
    void neitherCounterLosesAnIncrementUnderContention() throws Exception {
        assertThat(timeAtomicLong(64, 100_000)).isNotNegative();
        assertThat(timeLongAdder(64, 100_000)).isNotNegative();
    }

    // --- harness ----------------------------------------------------------

    /**
     * @throws AssertionError if any increment was lost - the check is inside the
     *         timed method on purpose, so no run can report a number without
     *         also having verified the total that produced it
     */
    private static long timeAtomicLong(int writers, int perWriter) throws Exception {
        AtomicLong counter = new AtomicLong();
        long elapsed = race(writers, () -> {
            for (int i = 0; i < perWriter; i++) {
                counter.incrementAndGet();
            }
        });
        assertThat(counter.get()).isEqualTo((long) writers * perWriter);
        return elapsed;
    }

    private static long timeLongAdder(int writers, int perWriter) throws Exception {
        LongAdder counter = new LongAdder();
        long elapsed = race(writers, () -> {
            for (int i = 0; i < perWriter; i++) {
                counter.increment();
            }
        });
        assertThat(counter.sum()).isEqualTo((long) writers * perWriter);
        return elapsed;
    }

    /**
     * Starts every writer, then releases them together.
     *
     * <p>The latch matters. Submitting N tasks and timing until they finish
     * measures the ramp as much as the contention - the first writer is often
     * done before the last one starts, so the contention being measured never
     * happens. Holding them all at the gate is what makes the window overlap.
     */
    private static long race(int writers, Runnable work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(writers);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < writers; i++) {
                pool.submit(() -> {
                    start.await();
                    work.run();
                    finished.countDown();
                    return null;
                });
            }

            long startedAt = System.nanoTime();
            start.countDown();
            assertThat(finished.await(120, TimeUnit.SECONDS))
                    .as("the writers must finish, or the number below means nothing")
                    .isTrue();
            return (System.nanoTime() - startedAt) / 1_000_000L;
        }
    }

    /** Each thread blocks inside a {@code synchronized} block on its OWN monitor. */
    private static long blockOnOwnMonitor(int threads, long blockMs) throws Exception {
        return blockEach(threads, () -> {
            Object own = new Object();
            return () -> {
                synchronized (own) {
                    sleep(blockMs);
                }
            };
        });
    }

    /** The same, on its own {@link ReentrantLock}. */
    private static long blockOnOwnLock(int threads, long blockMs) throws Exception {
        return blockEach(threads, () -> {
            ReentrantLock own = new ReentrantLock();
            return () -> {
                own.lock();
                try {
                    sleep(blockMs);
                } finally {
                    own.unlock();
                }
            };
        });
    }

    private static long blockEach(int threads,
                                  java.util.function.Supplier<Runnable> perThreadWork)
            throws Exception {

        CountDownLatch finished = new CountDownLatch(threads);
        long startedAt = System.nanoTime();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                Runnable work = perThreadWork.get();
                pool.submit(() -> {
                    work.run();
                    finished.countDown();
                });
            }
            assertThat(finished.await(120, TimeUnit.SECONDS)).isTrue();
        }
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
