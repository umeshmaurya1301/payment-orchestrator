package com.payorch.infra.idempotency;

import java.util.Optional;
import java.util.UUID;

/**
 * Storage for idempotency records. Implemented per service, against whatever
 * that service already persists to.
 *
 * <p>An interface rather than a concrete class because phase 7 adds a second
 * implementation - a Redis-backed in-flight marker in front of the durable
 * record - and {@link IdempotencyGuard} should not change when it does.
 *
 * <p>Uniqueness must be enforced by the <em>database</em>, on
 * {@code (merchant_id, idempotency_key)}. A read-then-write in application code
 * has a window between the two, and two concurrent requests with the same key
 * will both find nothing and both proceed. That window is not theoretical: it is
 * exactly what phase 7 measures under k6 load.
 */
public interface IdempotencyStore {

    /**
     * Inserts a record for this key, returning whether this caller won.
     *
     * @return {@code true} if the row was inserted by this call, {@code false}
     *         if a row already existed - which means another request got there
     *         first, whether a second ago or last week
     */
    boolean claim(UUID merchantId, String key);

    /** The stored response, if the winning request has finished. */
    Optional<ReplayableResponse> findResponse(UUID merchantId, String key);

    /** Attaches the rendered response to a claimed record. */
    void complete(UUID merchantId, String key, ReplayableResponse response);

    /**
     * Drops a claim whose work failed, so the caller can retry with the same
     * key.
     *
     * <p>Phase 1 releases eagerly on failure. Phase 7 replaces this with a TTL,
     * because eager release cannot cover the case this design is weakest at: a
     * process that dies mid-request leaves a claim nobody will ever release,
     * and that key is then permanently unusable.
     */
    void release(UUID merchantId, String key);
}
