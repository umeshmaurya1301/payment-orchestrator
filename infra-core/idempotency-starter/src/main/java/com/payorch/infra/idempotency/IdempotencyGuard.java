package com.payorch.infra.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
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
 * <h2>Phase 7b: a duplicate waits rather than being turned away</h2>
 *
 * <p>Until now a genuinely concurrent duplicate got a 409 immediately, and the
 * phase-7 exit criterion is not satisfiable that way: one hundred threads
 * sharing a key are supposed to produce <strong>one payment and ninety-nine
 * replayed responses</strong>, not one payment and ninety-nine errors. A 409
 * tells the caller nothing they can act on - the payment may or may not be
 * about to exist - and every one of those callers will retry, which is more
 * load arriving at exactly the moment the first request has not finished.
 *
 * <p>So a duplicate waits for the winner and then replays its answer. The wait
 * is bounded by {@link WaitBudget}, which in a real request is whatever is left
 * of the deadline phase 3a attached to it: a duplicate must never outlive the
 * caller who is waiting for it, and a request with 40ms left does not wait at
 * all.
 *
 * <p><strong>What this deliberately does not do yet.</strong> No Redis in-flight
 * marker, so every waiter polls the database. No TTL, so a process that dies
 * mid-request burns its key until somebody deletes the row. Both are later parts
 * of phase 7.
 */
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    /**
     * The shortest wait worth starting.
     *
     * <p>Below this there is no point: the first poll has not come back before
     * the budget is gone, so the caller pays the latency and still gets the 409.
     * The same reasoning as {@code DeadlineExecutor}'s minimum slice, and the
     * same conclusion - decline early rather than start something that cannot
     * finish.
     */
    private static final long MIN_WAIT_MS = 25;

    /** First poll interval. Doubles up to {@link #MAX_POLL_MS}. */
    private static final long FIRST_POLL_MS = 10;

    /**
     * The polling ceiling.
     *
     * <p>Backing off matters more than it looks. Every waiter polls the durable
     * store, so a hundred duplicates at a flat 10ms would put ten thousand
     * queries a second on the same row of the same table the winner is trying to
     * write to - the waiters would be slowing down the request they are waiting
     * for. Doubling turns a hundred waiters over two seconds into roughly eight
     * queries each rather than two hundred.
     */
    private static final long MAX_POLL_MS = 160;

    private final IdempotencyStore store;
    private final WaitBudget waits;

    public IdempotencyGuard(IdempotencyStore store, WaitBudget waits) {
        this.store = store;
        this.waits = waits;
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

            return existing.response()
                    // Still running. Wait for it rather than turning the caller
                    // away with an answer they cannot act on.
                    .orElseGet(() -> awaitWinner(merchantId, key, fingerprint));
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
     * Waits for the request that won the claim to store its response.
     *
     * <h2>Polling, and why that is the right shape here</h2>
     *
     * <p>The obvious alternative is to have the winner notify the waiters - a
     * condition variable, a Redis pub/sub channel. Both are correct within one
     * process and neither is, across several: the waiters for a key are spread
     * over however many instances of this service are running, and the winner
     * has no idea who they are. Polling a shared store is the only mechanism
     * that works for a duplicate that landed on a different node, which is the
     * normal case rather than the exotic one.
     *
     * <p>The cost is queries, which is why the interval doubles - see
     * {@link #MAX_POLL_MS}. It is also why phase 7's next step puts a Redis
     * marker in front of this: not for correctness, which the durable store
     * already has, but because a hundred waiters polling the row the winner is
     * writing to is load applied at the worst possible moment.
     *
     * <h2>The fingerprint is re-checked on every read</h2>
     *
     * <p>Not paranoia. The claim can be released and re-taken while this waits -
     * the winner fails, releases, and an unrelated request grabs the same key
     * with a different body. Replaying that response would hand this caller an
     * answer to somebody else's question.
     *
     * @throws InFlightException if the budget runs out first
     */
    private ReplayableResponse awaitWinner(UUID merchantId, String key, String fingerprint) {
        long budgetMs = Math.max(0, waits.remainingMs());
        if (budgetMs < MIN_WAIT_MS) {
            // Not enough time to be useful. Decline now, while the caller still
            // has some of their budget left to do something else with it.
            throw new InFlightException(key);
        }

        long deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L;
        long pollMs = FIRST_POLL_MS;

        while (System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException e) {
                // Restore the flag rather than swallowing it. Every service here
                // runs on virtual threads and drains on shutdown; a wait that
                // eats its own interrupt is one that will not stop.
                Thread.currentThread().interrupt();
                throw new InFlightException(key);
            }

            Optional<IdempotencyStore.Existing> existing = store.find(merchantId, key);
            if (existing.isEmpty()) {
                // The winner failed and released the claim. Nothing is running
                // and nothing is stored, so waiting longer cannot help - and the
                // caller retrying is exactly the right next move.
                throw new InFlightException(key);
            }

            requireSameRequest(merchantId, key, fingerprint, existing.get());

            if (existing.get().response().isPresent()) {
                return existing.get().response().get();
            }

            pollMs = Math.min(pollMs * 2, MAX_POLL_MS);
        }

        throw new InFlightException(key);
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
