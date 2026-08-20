package com.payorch.infra.idempotency;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
    private final IdempotencyGuard guard = new IdempotencyGuard(store);

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
    void aClaimHeldWithoutAResponseIsReportedAsInFlight() {
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

    private static ReplayableResponse response(String json) {
        return new ReplayableResponse(201, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    /** The contract the JPA implementation in payments-edge has to honour. */
    private static final class InMemoryStore implements IdempotencyStore {

        private record Id(UUID merchant, String key) {
        }

        private record Row(String fingerprint, Optional<ReplayableResponse> response) {
        }

        private final Map<Id, Row> records = new HashMap<>();

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
