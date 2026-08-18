package com.payorch.infra.chaos;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Named injection points, armed and disarmed at runtime.
 *
 * <p><strong>Deliberately thin.</strong> This is a test seam, not a framework.
 * It exists for exactly two faults that the other four chaos layers cannot
 * reach, and everything else - network faults, process kills, generic bean
 * latency - belongs to Toxiproxy, Pumba and Chaos Monkey respectively. Every
 * feature added here is one that should probably have been a toxic.
 *
 * <p>The other layers all inject <em>around</em> something: a connection, a
 * process, a bean method. These two faults need to happen at a specific line in
 * the middle of a method, while a lock is held or before an offset is
 * committed, and no amount of proxying reaches there.
 *
 * <p>Calls are free when disarmed - one hash lookup on a
 * {@link ConcurrentHashMap} that is empty in every normal run - which is what
 * makes it acceptable to leave the call sites in production code.
 */
public class ChaosSeams {

    private static final Logger log = LoggerFactory.getLogger(ChaosSeams.class);

    private final Map<String, ChaosSeam> armed = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> injections = new ConcurrentHashMap<>();

    /**
     * Runs whatever is armed for {@code name}, or returns immediately.
     *
     * @throws ChaosInjectedException if the seam is armed to fail and the roll
     *         goes against the caller
     */
    public void reach(String name) {
        ChaosSeam seam = armed.get(name);
        if (seam == null) {
            return;
        }
        // The roll happens per REACH, not per arming, so a message retried
        // three times gets three independent chances to get through. That is
        // what makes a partial failure rate produce a spread across the retry
        // tiers rather than a clean split into "worked" and "doomed".
        if (seam.probability() < ChaosSeam.ALWAYS
                && ThreadLocalRandom.current().nextDouble() >= seam.probability()) {
            return;
        }
        injections.computeIfAbsent(name, k -> new LongAdder()).increment();
        switch (seam.action()) {
            case PAUSE -> pause(name, seam.pauseMs());
            case FAIL -> {
                log.warn("chaos seam '{}' failing on purpose", name);
                throw new ChaosInjectedException(name);
            }
        }
    }

    /**
     * How many times each seam actually fired.
     *
     * <p>Not a nicety. With a probabilistic seam, "the experiment injected 30%
     * failures" is a claim about a random process, and the only way to state it
     * afterwards is to have counted. It also catches the failure mode that
     * wasted three drills in phase 5: a chaos source that was never actually
     * reached, producing a clean run that looks like a result.
     */
    public Map<String, Long> injections() {
        return injections.entrySet().stream().collect(
                Collectors.toMap(Map.Entry::getKey, e -> e.getValue().sum()));
    }

    public void arm(String name, ChaosSeam seam) {
        armed.put(name, seam);
        log.warn("chaos seam '{}' armed: {}", name, seam);
    }

    public void disarm(String name) {
        if (armed.remove(name) != null) {
            log.info("chaos seam '{}' disarmed", name);
        }
    }

    /**
     * Disarms everything.
     *
     * <p>Called from an experiment's teardown. A seam left armed between runs is
     * a second, invisible fault, and attributing a result to the wrong cause is
     * the failure mode this whole phase is organised to avoid.
     */
    public void disarmAll() {
        if (!armed.isEmpty()) {
            log.info("disarming {} chaos seam(s)", armed.size());
            armed.clear();
        }
        // Counts survive disarming on purpose: the experiment disarms the seam
        // BEFORE it replays and asserts, and it still needs to report how many
        // failures it injected.
    }

    public Map<String, ChaosSeam> armed() {
        return Map.copyOf(armed);
    }

    public Optional<ChaosSeam> armed(String name) {
        return Optional.ofNullable(armed.get(name));
    }

    private static void pause(String name, long millis) {
        log.warn("chaos seam '{}' pausing for {}ms", name, millis);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // Restore the flag and return rather than swallow. A seam that ate
            // an interrupt would make graceful shutdown hang during an
            // experiment, and the hang would look like the finding.
            Thread.currentThread().interrupt();
        }
    }

    /** Thrown by an armed {@link ChaosSeam.Action#FAIL} seam. */
    public static class ChaosInjectedException extends RuntimeException {

        public ChaosInjectedException(String seam) {
            super("chaos injected at seam '" + seam + "'");
        }
    }
}
