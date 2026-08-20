package com.payorch.infra.idempotency;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyGuardTest {

    private static final UUID MERCHANT = UUID.randomUUID();

    /**
     * A stand-in for a real fingerprint. The guard only ever compares these for
     * equality, so a readable literal makes the tests say what they mean -
     * {@link RequestFingerprintTest} is where the value itself is tested.
     */
    private static final String FP = "fingerprint-of-the-original-request";

    private final InMemoryStore store = new InMemoryStore();

    /**
     * Zero budget for most of these, so a duplicate that finds work in flight
     * fails immediately instead of sleeping.
     *
     * <p>Not a shortcut. A test that waited would be measuring the clock, and
     * the ones below are about which BRANCH is taken - the waiting itself has
     * its own tests, where the timing is the subject rather than a tax.
     */
    private final IdempotencyGuard guard =
            new IdempotencyGuard(store, WaitBudget.fixed(0));

    @Test
    void runsTheWorkOnceAndReplaysAfterwards() {
        AtomicInteger runs = new AtomicInteger();

        ReplayableResponse first = guard.execute(MERCHANT, "key-1", FP, () -> {
            runs.incrementAndGet();
            return response("{\"id\":\"a\"}");
        });
        ReplayableResponse second = guard.execute(MERCHANT, "key-1", FP, () -> {
            runs.incrementAndGet();
            return response("{\"id\":\"DIFFERENT\"}");
        });

        assertThat(runs).hasValue(1);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.status()).isEqualTo(first.status());
    }

    /**
     * Exit criterion 4 of phase 1, expressed as a unit test: the replayed body
     * is byte-identical, not merely equivalent.
     */
    @Test
    void replayIsByteIdentical() {
        ReplayableResponse first = guard.execute(MERCHANT, "key-1", FP,
                () -> response("{\"b\":2,\"a\":1}"));
        ReplayableResponse replayed = guard.execute(MERCHANT, "key-1", FP,
                () -> response("{\"a\":1,\"b\":2}"));

        assertThat(new String(replayed.body(), StandardCharsets.UTF_8)).isEqualTo("{\"b\":2,\"a\":1}");
        assertThat(replayed.body()).isEqualTo(first.body());
    }

    @Test
    void keysAreScopedToTheMerchant() {
        UUID other = UUID.randomUUID();

        guard.execute(MERCHANT, "shared-key", FP, () -> response("{\"who\":\"first\"}"));
        ReplayableResponse second = guard.execute(other, "shared-key", FP,
                () -> response("{\"who\":\"second\"}"));

        assertThat(new String(second.body(), StandardCharsets.UTF_8)).contains("second");
    }

    @Test
    void aClaimHeldWithoutAResponseIsReportedAsInFlightWhenThereIsNoTimeToWait() {
        store.claim(MERCHANT, "key-1", FP);

        assertThatThrownBy(() -> guard.execute(MERCHANT, "key-1", FP, () -> response("{}")))
                .isInstanceOf(IdempotencyGuard.InFlightException.class)
                .hasMessageContaining("key-1");
    }

    /**
     * A failed attempt must not burn the key. Without the release, a caller
     * whose first attempt hit a downstream error could never retry with the
     * same key and would have to invent a new one - which is precisely the
     * behaviour idempotency keys exist to make unnecessary.
     */
    @Test
    void aFailedAttemptReleasesTheClaim() {
        assertThatThrownBy(() -> guard.execute(MERCHANT, "key-1", FP, () -> {
            throw new IllegalStateException("downstream exploded");
        })).isInstanceOf(IllegalStateException.class);

        ReplayableResponse retried = guard.execute(MERCHANT, "key-1", FP,
                () -> response("{\"ok\":true}"));

        assertThat(new String(retried.body(), StandardCharsets.UTF_8)).contains("ok");
    }

    // --- phase 7a: the request is compared, not assumed --------------------

    /**
     * THE BUG 7A CLOSES.
     *
     * <p>Before the fingerprint, this returned the stored 201 - the merchant
     * asks to charge one amount, receives a success for a different one, and
     * nothing anywhere records that the second request was never performed.
     */
    @Test
    void aReusedKeyWithADifferentRequestIsRejectedRatherThanReplayed() {
        guard.execute(MERCHANT, "key-1", "fingerprint-of-INR-42",
                () -> response("{\"amountMinor\":42}"));

        assertThatThrownBy(() -> guard.execute(MERCHANT, "key-1", "fingerprint-of-INR-50000",
                () -> response("{\"amountMinor\":5000000}")))
                .isInstanceOf(IdempotencyGuard.FingerprintMismatchException.class)
                .hasMessageContaining("key-1");
    }

    /** The work must not run either - a rejected request has done nothing. */
    @Test
    void aRejectedReuseDoesNotRunTheWork() {
        AtomicInteger runs = new AtomicInteger();
        guard.execute(MERCHANT, "key-1", "fingerprint-a", () -> {
            runs.incrementAndGet();
            return response("{}");
        });

        assertThatThrownBy(() -> guard.execute(MERCHANT, "key-1", "fingerprint-b", () -> {
            runs.incrementAndGet();
            return response("{}");
        })).isInstanceOf(IdempotencyGuard.FingerprintMismatchException.class);

        assertThat(runs).hasValue(1);
    }

    /**
     * The mismatch is checked BEFORE the in-flight state, deliberately.
     *
     * <p>A reused key is wrong whether or not the first request has finished,
     * and "your key is reused for a different request" is more useful than "try
     * again shortly" - which invites exactly the retry that will fail the same
     * way, and would turn a client bug into a retry loop.
     */
    @Test
    void aMismatchOnAnInFlightClaimIsAMismatchAndNotAConflict() {
        store.claim(MERCHANT, "key-1", "fingerprint-a");

        assertThatThrownBy(() -> guard.execute(MERCHANT, "key-1", "fingerprint-b",
                () -> response("{}")))
                .isInstanceOf(IdempotencyGuard.FingerprintMismatchException.class);
    }

    /**
     * The deploy window. A record written before 7a has no fingerprint and
     * cannot be backfilled - the request bodies were never stored, because they
     * contain card numbers.
     *
     * <p>It replays rather than 422s, because the failure that allows was
     * already possible yesterday, while the 422 would break retries that work
     * today. A deploy that turns healthy requests into errors is the worse
     * trade.
     */
    @Test
    void aRecordFromBeforeFingerprintingStillReplays() {
        store.claimLegacy(MERCHANT, "key-1");
        store.complete(MERCHANT, "key-1", response("{\"legacy\":true}"));

        ReplayableResponse replayed = guard.execute(MERCHANT, "key-1", FP,
                () -> response("{\"fresh\":true}"));

        assertThat(new String(replayed.body(), StandardCharsets.UTF_8)).contains("legacy");
    }

    /**
     * The claim was lost to a row that has since vanished - the winner failed
     * and released it in between. Nothing is stored and nothing is running, so
     * the answer has to be the retryable one rather than a NoSuchElement.
     */
    @Test
    void aClaimThatVanishesBetweenTheInsertAndTheReadIsInFlight() {
        store.vanishOnFind = true;
        store.claim(MERCHANT, "key-1", FP);

        assertThatThrownBy(() -> guard.execute(MERCHANT, "key-1", FP, () -> response("{}")))
                .isInstanceOf(IdempotencyGuard.InFlightException.class);
    }

    // --- phase 7b: a duplicate waits rather than being turned away ---------

    /**
     * THE EXIT CRITERION, at the guard.
     *
     * <p>One hundred threads, one key: exactly one runs the work and
     * ninety-nine receive that same response. Before 7b this produced one
     * payment and ninety-nine 409s, which does not satisfy the criterion and is
     * not much use to a caller either - a 409 says the payment may or may not be
     * about to exist, and every one of those callers retries into the request
     * that has not finished yet.
     *
     * <p>Genuinely concurrent, not a loop. Phase 7's own trap list names this:
     * a hundred sequential requests all take the replay path and prove nothing,
     * because the interesting window is the one where the winner has claimed and
     * not yet completed.
     */
    @Test
    void oneHundredConcurrentRequestsProduceOneRunAndNinetyNineReplays() throws Exception {
        IdempotencyGuard waiting = new IdempotencyGuard(store, WaitBudget.fixed(5_000));

        int threads = 100;
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReplayableResponse>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return waiting.execute(MERCHANT, "hot-key", FP, () -> {
                        runs.incrementAndGet();
                        // Long enough that the other 99 are certainly inside the
                        // wait rather than arriving after the work is done -
                        // which is the difference between testing the wait and
                        // testing the replay path that already existed.
                        sleep(150);
                        return response("{\"id\":\"the-one\"}");
                    });
                }));
            }
            start.countDown();

            List<byte[]> bodies = new ArrayList<>();
            for (Future<ReplayableResponse> result : results) {
                bodies.add(result.get(30, TimeUnit.SECONDS).body());
            }

            assertThat(runs)
                    .as("exactly one request may do the work")
                    .hasValue(1);
            assertThat(bodies).hasSize(threads);
            assertThat(bodies)
                    .as("every response must be the winner's, byte for byte")
                    .allSatisfy(body -> assertThat(new String(body, StandardCharsets.UTF_8))
                            .isEqualTo("{\"id\":\"the-one\"}"));
        }
    }

    /** A duplicate whose budget runs out before the winner finishes still gets a 409. */
    @Test
    void aWaitThatOutlastsItsBudgetIsStillAConflict() {
        IdempotencyGuard waiting = new IdempotencyGuard(store, WaitBudget.fixed(120));
        store.claim(MERCHANT, "key-1", FP);

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> waiting.execute(MERCHANT, "key-1", FP, () -> response("{}")))
                .isInstanceOf(IdempotencyGuard.InFlightException.class);

        long waitedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        assertThat(waitedMs)
                .as("it must actually have waited, and must not have waited much beyond its budget")
                .isBetween(100L, 2_000L);
    }

    /**
     * A budget under the minimum declines immediately rather than sleeping once
     * and failing anyway - the same reasoning as the deadline executor's minimum
     * slice. The caller keeps what little time they had left.
     */
    @Test
    void aBudgetTooSmallToBeUsefulDeclinesWithoutWaiting() {
        IdempotencyGuard waiting = new IdempotencyGuard(store, WaitBudget.fixed(5));
        store.claim(MERCHANT, "key-1", FP);

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> waiting.execute(MERCHANT, "key-1", FP, () -> response("{}")))
                .isInstanceOf(IdempotencyGuard.InFlightException.class);

        assertThat((System.nanoTime() - startedAt) / 1_000_000L)
                .as("below the minimum it must not sleep at all")
                .isLessThan(25L);
    }

    /** A negative budget - a request already past its deadline - is not a long wait. */
    @Test
    void anExpiredDeadlineDoesNotWrapIntoAnEnormousWait() {
        IdempotencyGuard waiting = new IdempotencyGuard(store, WaitBudget.fixed(-5_000));
        store.claim(MERCHANT, "key-1", FP);

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> waiting.execute(MERCHANT, "key-1", FP, () -> response("{}")))
                .isInstanceOf(IdempotencyGuard.InFlightException.class);

        assertThat((System.nanoTime() - startedAt) / 1_000_000L).isLessThan(25L);
    }

    /**
     * The winner failed and released the claim while this one was waiting.
     * Nothing is running and nothing is stored, so waiting longer cannot help -
     * and the caller retrying is the right next move rather than a slow failure.
     */
    @Test
    void aWaiterWhoseWinnerFailsIsToldPromptlyRatherThanWaitingOut() throws Exception {
        IdempotencyGuard waiting = new IdempotencyGuard(store, WaitBudget.fixed(10_000));
        store.claim(MERCHANT, "key-1", FP);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            pool.submit(() -> {
                sleep(100);
                store.release(MERCHANT, "key-1");
            });

            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> waiting.execute(MERCHANT, "key-1", FP, () -> response("{}")))
                    .isInstanceOf(IdempotencyGuard.InFlightException.class);

            assertThat((System.nanoTime() - startedAt) / 1_000_000L)
                    .as("it must not sit out the whole ten seconds")
                    .isLessThan(5_000L);
        }
    }

    /**
     * THE RACE THE WAIT INTRODUCES.
     *
     * <p>A claim can be released and re-taken while a duplicate is waiting on
     * it: the winner fails, releases, and an unrelated request grabs the same
     * key with a different body. Replaying that response would hand this caller
     * an answer to somebody else's question, so the fingerprint is re-checked on
     * every poll rather than only on entry.
     */
    @Test
    void aClaimRetakenByADifferentRequestMidWaitIsAMismatchAndNotAReplay() throws Exception {
        IdempotencyGuard waiting = new IdempotencyGuard(store, WaitBudget.fixed(10_000));
        store.claim(MERCHANT, "key-1", "fingerprint-a");

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            pool.submit(() -> {
                sleep(100);
                store.release(MERCHANT, "key-1");
                store.claim(MERCHANT, "key-1", "fingerprint-b");
                store.complete(MERCHANT, "key-1", response("{\"someone\":\"else\"}"));
            });

            assertThatThrownBy(() -> waiting.execute(MERCHANT, "key-1", "fingerprint-a",
                    () -> response("{}")))
                    .isInstanceOf(IdempotencyGuard.FingerprintMismatchException.class);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static ReplayableResponse response(String json) {
        return new ReplayableResponse(201, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    /** The contract the JPA implementation in payments-edge has to honour. */
    private static final class InMemoryStore implements IdempotencyStore {

        private record Id(UUID merchant, String key) {
        }

        private record Row(String fingerprint, Optional<ReplayableResponse> response) {
        }

        // Concurrent, because oneHundredConcurrentRequestsProduceOneRunAndNinetyNineReplays
        // hammers it from a hundred virtual threads. putIfAbsent on a
        // ConcurrentHashMap is atomic, which is what makes it a fair stand-in
        // for the unique constraint it is imitating - a HashMap here would let
        // two callers both win the claim and the test would be measuring a
        // broken double rather than the guard.
        private final Map<Id, Row> records = new ConcurrentHashMap<>();

        /** Simulates the winner releasing its claim between the insert and the read. */
        private boolean vanishOnFind;

        @Override
        public boolean claim(UUID merchantId, String key, String fingerprint) {
            return records.putIfAbsent(new Id(merchantId, key),
                    new Row(fingerprint, Optional.empty())) == null;
        }

        /** A row as it would have been written before phase 7a: no fingerprint. */
        void claimLegacy(UUID merchantId, String key) {
            records.put(new Id(merchantId, key), new Row(null, Optional.empty()));
        }

        @Override
        public Optional<Existing> find(UUID merchantId, String key) {
            if (vanishOnFind) {
                return Optional.empty();
            }
            return Optional.ofNullable(records.get(new Id(merchantId, key)))
                    .map(row -> new Existing(row.fingerprint(), row.response()));
        }

        @Override
        public void complete(UUID merchantId, String key, ReplayableResponse response) {
            records.computeIfPresent(new Id(merchantId, key),
                    (id, row) -> new Row(row.fingerprint(), Optional.of(response)));
        }

        @Override
        public void release(UUID merchantId, String key) {
            records.remove(new Id(merchantId, key));
        }
    }
}
