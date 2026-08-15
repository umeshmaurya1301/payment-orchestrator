package com.payorch.infra.chaos;

/**
 * What an armed seam does when execution reaches it.
 *
 * @param action  pause or fail
 * @param pauseMs how long to sleep, for {@link Action#PAUSE}
 */
public record ChaosSeam(Action action, long pauseMs) {

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
        return new ChaosSeam(Action.PAUSE, millis);
    }

    public static ChaosSeam fail() {
        return new ChaosSeam(Action.FAIL, 0);
    }
}
