package com.payorch.infra.idempotency;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs a unit of work at most once per {@code (merchant, idempotency key)},
 * replaying the stored response for every repeat.
 *
 * <p>The order of operations matters. The claim is attempted <em>first</em>, as
 * a single insert, and the read only happens when that insert loses. Checking
 * first and inserting second would look more natural and would be wrong: two
 * concurrent requests would both read nothing, both proceed, and the constraint
 * violation would surface from inside the work rather than in front of it.
 *
 * <p><strong>What this deliberately does not do yet.</strong> No request-body
 * fingerprinting, so reusing a key with a different payload replays the first
 * response instead of being rejected. No Redis in-flight marker, so a genuinely
 * concurrent duplicate gets {@link InFlightException} rather than a considered
 * 409. No TTL, so records live forever. All three are phase 7, and all three
 * exist to solve concurrency problems that have not been measured yet - adding
 * them now would mean guessing at their parameters instead of reading them off
 * a graph.
 */
public class IdempotencyGuard {

    private final IdempotencyStore store;

    public IdempotencyGuard(IdempotencyStore store) {
        this.store = store;
    }

    /**
     * @param work runs only if this call wins the claim. Its result is stored
     *             verbatim and returned; every later call with the same key gets
     *             those same bytes back.
     * @throws InFlightException if another request holds the claim and has not
     *         yet stored a response
     */
    public ReplayableResponse execute(UUID merchantId, String key, Supplier<ReplayableResponse> work) {
        if (!store.claim(merchantId, key)) {
            return store.findResponse(merchantId, key)
                    .orElseThrow(() -> new InFlightException(key));
        }

        ReplayableResponse response;
        try {
            response = work.get();
        } catch (RuntimeException e) {
            // Without this the key is burned: the claim row exists, no response
            // was ever stored, and every retry with the same key gets 409
            // forever. Phase 7's TTL is the durable answer; this is the honest
            // phase-1 one.
            store.release(merchantId, key);
            throw e;
        }

        store.complete(merchantId, key, response);
        return response;
    }

    /** A request with this key is already running. */
    public static class InFlightException extends RuntimeException {

        public InFlightException(String key) {
            super("a request with idempotency key '" + key + "' is already in progress");
        }
    }
}
