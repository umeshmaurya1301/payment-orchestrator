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

    private final InMemoryStore store = new InMemoryStore();
    private final IdempotencyGuard guard = new IdempotencyGuard(store);

    @Test
    void runsTheWorkOnceAndReplaysAfterwards() {
        AtomicInteger runs = new AtomicInteger();

        ReplayableResponse first = guard.execute(MERCHANT, "key-1", () -> {
            runs.incrementAndGet();
            return response("{\"id\":\"a\"}");
        });
        ReplayableResponse second = guard.execute(MERCHANT, "key-1", () -> {
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
        ReplayableResponse first = guard.execute(MERCHANT, "key-1", () -> response("{\"b\":2,\"a\":1}"));
        ReplayableResponse replayed = guard.execute(MERCHANT, "key-1", () -> response("{\"a\":1,\"b\":2}"));

        assertThat(new String(replayed.body(), StandardCharsets.UTF_8)).isEqualTo("{\"b\":2,\"a\":1}");
    }

    @Test
    void keysAreScopedToTheMerchant() {
        UUID other = UUID.randomUUID();

        guard.execute(MERCHANT, "shared-key", () -> response("{\"who\":\"first\"}"));
        ReplayableResponse second = guard.execute(other, "shared-key", () -> response("{\"who\":\"second\"}"));

        assertThat(new String(second.body(), StandardCharsets.UTF_8)).contains("second");
    }

    @Test
    void aClaimHeldWithoutAResponseIsReportedAsInFlight() {
        store.claim(MERCHANT, "key-1");

        assertThatThrownBy(() -> guard.execute(MERCHANT, "key-1", () -> response("{}")))
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
        assertThatThrownBy(() -> guard.execute(MERCHANT, "key-1", () -> {
            throw new IllegalStateException("downstream exploded");
        })).isInstanceOf(IllegalStateException.class);

        ReplayableResponse retried = guard.execute(MERCHANT, "key-1", () -> response("{\"ok\":true}"));

        assertThat(new String(retried.body(), StandardCharsets.UTF_8)).contains("ok");
    }

    private static ReplayableResponse response(String json) {
        return new ReplayableResponse(201, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    /** The contract the JPA implementation in payments-edge has to honour. */
    private static final class InMemoryStore implements IdempotencyStore {

        private record Id(UUID merchant, String key) {
        }

        private final Map<Id, Optional<ReplayableResponse>> records = new HashMap<>();

        @Override
        public boolean claim(UUID merchantId, String key) {
            return records.putIfAbsent(new Id(merchantId, key), Optional.empty()) == null;
        }

        @Override
        public Optional<ReplayableResponse> findResponse(UUID merchantId, String key) {
            return records.getOrDefault(new Id(merchantId, key), Optional.empty());
        }

        @Override
        public void complete(UUID merchantId, String key, ReplayableResponse response) {
            records.put(new Id(merchantId, key), Optional.of(response));
        }

        @Override
        public void release(UUID merchantId, String key) {
            records.remove(new Id(merchantId, key));
        }
    }
}
