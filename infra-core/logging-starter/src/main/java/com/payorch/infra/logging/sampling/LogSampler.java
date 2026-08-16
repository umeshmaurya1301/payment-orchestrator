package com.payorch.infra.logging.sampling;

/**
 * Decides whether a trace's ordinary log lines are worth keeping.
 *
 * <p>Measured before this existed: <strong>4.00 log lines and 2,514 bytes per
 * payment</strong> across the three services that carry one, on a clean run with
 * no chaos. At the 500 rps phase 2 established as this system's breaking point
 * that is <strong>75 MB per minute</strong> - about 108 GB a day - to record that
 * several million things went exactly as expected.
 *
 * <h2>The decision is a hash of the traceId, and that is the whole design</h2>
 *
 * <p>Every service hashes the same trace id and gets the same answer, with no
 * coordination, no shared state and no extra field on the wire. A sampled trace
 * is therefore kept <em>completely</em> - all four lines, across all four
 * services - and a dropped one is dropped completely.
 *
 * <p>The alternative, sampling per line, produces the worst possible artefact:
 * 1% of the lines of 100% of the traces, so every trace is present and none is
 * readable. That is strictly less useful than keeping nothing, because it looks
 * like evidence.
 *
 * <p>It also means the decision is stable under retry and re-reading. The same
 * trace id always yields the same verdict, so a log that was kept stays kept.
 *
 * <h2>What is never sampled</h2>
 *
 * <ul>
 *   <li><strong>WARN and above.</strong> Handled in {@link SamplingTurboFilter}
 *       before this class is consulted. The phase-4 requirement is 100% of
 *       errors, and an error rate is not something to estimate from 1% of the
 *       evidence.</li>
 *   <li><strong>Anything with no trace id.</strong> Startup, shutdown and
 *       scheduled work. This project logs its effective configuration at
 *       startup, and those lines are what every experiment writeup quotes to
 *       prove which build produced its numbers. They are also negligible in
 *       volume: a few dozen lines against millions.</li>
 * </ul>
 */
public final class LogSampler {

    /** Denominator for the fixed-point comparison below. */
    private static final long SCALE = 1_000_000L;

    private final double successRate;
    private final long threshold;

    public LogSampler(double successRate) {
        if (successRate < 0.0 || successRate > 1.0) {
            throw new IllegalArgumentException(
                    "log sampling rate must be between 0.0 and 1.0, got " + successRate);
        }
        this.successRate = successRate;
        this.threshold = (long) (successRate * SCALE);
    }

    /** 1.0 - keep everything. The default, for the reasons in the autoconfiguration. */
    public static LogSampler keepEverything() {
        return new LogSampler(1.0);
    }

    public boolean enabled() {
        return successRate < 1.0;
    }

    public double successRate() {
        return successRate;
    }

    /**
     * Whether this trace's ordinary lines are kept.
     *
     * <p>Reads the low 52 bits of the trace id rather than {@code hashCode()}.
     * A W3C trace id is 32 hex characters of randomness, so its low bits are
     * already uniformly distributed - hashing them again would add cost without
     * adding entropy, and would make the verdict depend on a JDK implementation
     * detail rather than on the id itself.
     *
     * <p>Non-hex or short ids are kept rather than dropped. A malformed trace id
     * means something upstream is wrong, and that is not the moment to start
     * discarding evidence.
     */
    public boolean keep(String traceId) {
        // FIRST, before any rate shortcut. Ordering these the other way round -
        // rate checks first, because they are cheaper - was the first version,
        // and it meant a null trace id was dropped at rate 0.0 while being kept
        // at every other rate. The "keep what you cannot classify" rule has to
        // hold at every rate or it is not a rule; a test caught it.
        if (traceId == null || traceId.length() < 13) {
            return true;
        }
        if (successRate >= 1.0) {
            return true;
        }
        long bits;
        try {
            bits = Long.parseLong(traceId.substring(traceId.length() - 13), 16);
        } catch (NumberFormatException e) {
            return true;
        }
        if (successRate <= 0.0) {
            return false;
        }
        return Math.floorMod(bits, SCALE) < threshold;
    }
}
