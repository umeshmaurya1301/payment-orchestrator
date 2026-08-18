package com.payorch.infra.chaos;

/**
 * What an armed seam does when execution reaches it.
 *
 * @param action      pause or fail
 * @param pauseMs     how long to sleep, for {@link Action#PAUSE}
 * @param probability chance, 0.0 to 1.0, that reaching the seam does anything
 */
public record ChaosSeam(Action action, long pauseMs, double probability) {

    /**
     * A seam armed without a stated probability fires every time. That is the
     * right default for the two faults this class was built for - a deadlock is
     * demonstrated by making it happen, not by making it likely.
     */
    public static final double ALWAYS = 1.0;

    public ChaosSeam {
        if (probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException(
                    "probability must be between 0.0 and 1.0, got " + probability);
        }
    }

    public enum Action {

        /**
         * Sleep, in place, on the calling thread.
         *
         * <p>The whole value is <em>where</em> the sleep happens. A generic
         * latency injector delays a call; this delays the thread at a chosen
         * line - specifically, one inside a transaction that is already holding
         * a {@code SELECT ... FOR UPDATE} lock. That turns a deadlock from
         * something that happens occasionally under load into something that
         * happens on demand, which is the difference between a phase-7
         * experiment and a phase-7 anecdote.
         */
        PAUSE,

        /**
         * Throw at this exact point.
         *
         * <p>For the phase-6 case Chaos Monkey cannot express: failing one named
         * {@code @KafkaListener} and no other bean, after the message has been
         * polled but before the offset is committed. That is the window where
         * at-least-once delivery either works or quietly loses a message, and
         * assaulting "all listener beans" would tell you nothing about which
         * one.
         */
        FAIL
    }

    public static ChaosSeam pause(long millis) {
        return new ChaosSeam(Action.PAUSE, millis, ALWAYS);
    }

    public static ChaosSeam fail() {
        return new ChaosSeam(Action.FAIL, 0, ALWAYS);
    }

    /**
     * Fails a fraction of the time.
     *
     * <p>Added for phase 6f, and the reason is worth stating because "make it
     * fail 30% of the time" sounds like a detail and is not. A seam that fails
     * <em>every</em> message sends every message to the DLQ, which proves the
     * DLQ works and proves nothing about the retry tiers - the interesting
     * assertion is that some messages recover at tier 1, some at tier 2, and
     * only the persistently unlucky ones exhaust the ladder. That distribution
     * only exists if each retry gets an independent roll.
     *
     * <p>It also means a run is not reproducible message-for-message, which is a
     * real cost. The experiment is written to assert on invariants that hold for
     * any seed - the books balance, no event posts twice - rather than on
     * counts, which would be a flaky test wearing an experiment's clothes.
     */
    public static ChaosSeam fail(double probability) {
        return new ChaosSeam(Action.FAIL, 0, probability);
    }

    public static ChaosSeam pause(long millis, double probability) {
        return new ChaosSeam(Action.PAUSE, millis, probability);
    }
}
