package com.payorch.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RollingOutcomesTest {

    @Test
    @DisplayName("an empty window reports -1, not 0.0 and not 1.0")
    void emptyWindowIsNotAVerdict() {
        RollingOutcomes outcomes = new RollingOutcomes(60);

        // The distinction the health scorer depends on: "no calls" and "every
        // call failed" must not arrive as the same number.
        assertThat(outcomes.successRate("psp-a")).isEqualTo(-1);
        assertThat(outcomes.samples("psp-a")).isZero();
    }

    @Test
    @DisplayName("success rate reflects what was recorded")
    void countsWhatItIsGiven() {
        RollingOutcomes outcomes = new RollingOutcomes(60);
        for (int i = 0; i < 75; i++) {
            outcomes.record("psp-a", true);
        }
        for (int i = 0; i < 25; i++) {
            outcomes.record("psp-a", false);
        }

        assertThat(outcomes.samples("psp-a")).isEqualTo(100);
        assertThat(outcomes.successRate("psp-a")).isCloseTo(0.75, within(0.001));
    }

    @Test
    @DisplayName("providers are tracked independently")
    void keysDoNotBleed() {
        RollingOutcomes outcomes = new RollingOutcomes(60);
        outcomes.record("psp-a", true);
        outcomes.record("psp-b", false);

        assertThat(outcomes.successRate("psp-a")).isEqualTo(1.0);
        assertThat(outcomes.successRate("psp-b")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("concurrent recording loses nothing")
    void isSafeUnderConcurrency() throws Exception {
        // This is incremented on the hot path of every provider call from many
        // virtual threads at once. A lost update here is a routing decision made
        // on a number that never existed - and the slot-reclaim compareAndSet is
        // exactly the kind of code that looks right and drops writes.
        RollingOutcomes outcomes = new RollingOutcomes(60);
        int threads = 16;
        int perThread = 2_000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int id = t;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        boolean ok = (id + i) % 4 != 0;
                        if (ok) {
                            successes.incrementAndGet();
                        }
                        outcomes.record("psp-a", ok);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        long expected = (long) threads * perThread;
        assertThat(outcomes.samples("psp-a")).isEqualTo(expected);
        assertThat(outcomes.successRate("psp-a"))
                .isCloseTo((double) successes.get() / expected, within(0.001));
    }
}
