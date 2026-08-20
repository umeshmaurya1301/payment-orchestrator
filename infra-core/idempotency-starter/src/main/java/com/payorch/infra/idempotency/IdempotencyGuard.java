package com.payorch.infra.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <h2>Phase 7a: the request is compared, not assumed</h2>
 *
 * <p>Until now a repeat was replayed on the strength of its key alone. Reusing a
 * key with a different body - a client bug, a copy-pasted curl, a retry loop
 * that rebuilds its request - returned the first response as though the second
 * request had been honoured. The merchant asks to charge INR 50,000, gets back
 * the stored 201 for the INR 42 payment from an hour ago, and both sides now
 * believe something happened that did not.
 *
 * <p>That is a client bug, and the fix is to <strong>say so</strong> rather than
 * to hide it. A 422 tells the caller their key is reused; a replay tells them
 * their payment succeeded.
 *
 * <p><strong>The comparison happens before the in-flight check, deliberately.</strong>
 * A mismatched fingerprint is wrong whether or not the first request has
 * finished, and "your key is reused for a different request" is a more useful
 * answer than "try again shortly" - which invites exactly the retry that will
 * fail the same way.
 *
 * <p><strong>What this deliberately does not do yet.</strong> No Redis in-flight
 * marker, so a genuinely concurrent duplicate gets {@link InFlightException}
 * immediately rather than after a bounded wait. No TTL, so records live forever
 * and a process that dies mid-request burns its key. Both are later parts of
 * phase 7.
 */
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    private final IdempotencyStore store;

    public IdempotencyGuard(IdempotencyStore store) {
        this.store = store;
    }

    /**
     * @param fingerprint what this request is asking for, from
     *                    {@link RequestFingerprint}
     * @param work        runs only if this call wins the claim. Its result is
     *                    stored verbatim and returned; every later call with the
     *                    same key and the same fingerprint gets those same bytes
     * @throws FingerprintMismatchException if the key was claimed by a request
     *         that asked for something else
     * @throws InFlightException if another request holds the claim and has not
     *         yet stored a response
     */
    public ReplayableResponse execute(UUID merchantId, String key, String fingerprint,
                                      Supplier<ReplayableResponse> work) {

        if (!store.claim(merchantId, key, fingerprint)) {
            IdempotencyStore.Existing existing = store.find(merchantId, key)
                    // The claim lost to a row that has since vanished - a
                    // release() from the winner's failure path landing between
                    // the two statements. Nothing is stored and nothing is
                    // running, so the honest answer is the retryable one.
                    .orElseThrow(() -> new InFlightException(key));

            requireSameRequest(merchantId, key, fingerprint, existing);

            return existing.response().orElseThrow(() -> new InFlightException(key));
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

    /**
     * @throws FingerprintMismatchException unless the stored request and this
     *         one are the same request
     */
    private void requireSameRequest(UUID merchantId, String key, String fingerprint,
                                    IdempotencyStore.Existing existing) {

        // A record written before fingerprinting existed. It cannot be compared
        // against anything, and the two ways of handling it are both bad: 422
        // makes every in-flight retry fail across the deploy that adds this,
        // and replaying is the phase-1 behaviour this method exists to end.
        //
        // Replaying wins, because the failure it allows is one that was already
        // possible yesterday, while the 422 would BREAK requests that work
        // today - a deploy that turns healthy retries into errors is a worse
        // trade than a deploy that leaves an old hole open for as long as the
        // old records live. Logged at WARN so the population is visible and the
        // window is known to be closing rather than assumed to be.
        if (existing.fingerprint() == null || existing.fingerprint().isEmpty()) {
            log.warn("replaying an idempotency record that predates fingerprinting "
                    + "- merchant {}, key length {}", merchantId, key.length());
            return;
        }

        // Constant-time, and honestly not load-bearing: the fingerprint is
        // derived from the caller's own body rather than presented by them, so
        // there is no secret here for a timing oracle to leak. Used because it
        // costs nothing and because the day this value does become
        // caller-presented, nobody will remember to change it.
        boolean same = MessageDigest.isEqual(
                existing.fingerprint().getBytes(StandardCharsets.UTF_8),
                fingerprint.getBytes(StandardCharsets.UTF_8));

        if (!same) {
            throw new FingerprintMismatchException(key);
        }
    }

    /** A request with this key is already running. */
    public static class InFlightException extends RuntimeException {

        public InFlightException(String key) {
            super("a request with idempotency key '" + key + "' is already in progress");
        }
    }

    /**
     * The key has been used before, for something else.
     *
     * <p>The message deliberately carries the key and nothing about either
     * body. A diff would be the most useful thing a developer could read and
     * also the fastest route from a card number to a log index - the bodies this
     * compares are the ones that contain PANs.
     */
    public static class FingerprintMismatchException extends RuntimeException {

        public FingerprintMismatchException(String key) {
            super("idempotency key '" + key + "' was already used for a different request");
        }
    }
}
