package com.payorch.infra.idempotency;

/**
 * How long a duplicate may wait for the request that beat it. Phase 7b.
 *
 * <h2>Why this is an interface and not a number</h2>
 *
 * <p>Because the right answer is not a constant - it is whatever is left of the
 * current request's deadline. Phase 3a made every inbound request carry a
 * budget, and a wait that ignored it would be the one unbounded thing left in a
 * system built around not having any: a duplicate could sit here for two seconds
 * while the caller had two hundred milliseconds left, and the reply would be
 * written to a connection nobody was reading.
 *
 * <p>It is not simply {@code Deadlines.current()} because that lives in
 * {@code resilience-starter}, and an idempotency library that could not be used
 * without the resilience one would be two libraries pretending to be separable.
 * The service that has both wires them together; the default here is a fixed
 * fallback, which is worse and honest about it.
 */
@FunctionalInterface
public interface WaitBudget {

    /**
     * @return milliseconds still available to this request, or {@code 0} if it
     *         should not wait at all
     */
    long remainingMs();

    /**
     * A fixed budget, for a caller with no deadline to consult.
     *
     * <p>Deliberately short. A fallback that waited as long as a real request
     * would is a fallback that hides its own absence - the failure it produces
     * is a slow reply rather than a missing configuration, and slow replies get
     * blamed on the network.
     */
    static WaitBudget fixed(long millis) {
        return () -> millis;
    }
}
