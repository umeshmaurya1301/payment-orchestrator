package com.payorch.infra.idempotency;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Contributes an {@link IdempotencyGuard} to any service that supplies an
 * {@link IdempotencyStore}.
 *
 * <p>Phase 1 puts the basic form here: a unique constraint on
 * {@code (merchant_id, idempotency_key)} plus response replay. Phase 7 hardens
 * it into the version that survives concurrency - request-body fingerprinting
 * so key reuse with a changed payload is caught rather than silently replayed,
 * a Redis in-flight marker returning 409 while the first request is still
 * running, and TTL expiry.
 *
 * <p>The store is left to the service because it is the service that knows
 * which database it already owns. Only {@code payments-edge} has one today.
 */
@AutoConfiguration
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnBean(IdempotencyStore.class)
    @ConditionalOnMissingBean
    public IdempotencyGuard idempotencyGuard(IdempotencyStore store) {
        return new IdempotencyGuard(store);
    }

    /**
     * Phase 7a. The keyed fingerprint.
     *
     * <p>No default for the secret, and the constructor throws on a blank one.
     * That is a deliberate startup failure rather than an inconvenience: a
     * fingerprint computed with an empty key is a plain SHA-256 of a request
     * body containing a card number, and with the BIN and last four stored in
     * plain text beside it that is under a million candidates to try. A control
     * that silently degrades to no control is worse than one that refuses to
     * start, because only one of them tells you.
     */
    @Bean
    @ConditionalOnBean(IdempotencyStore.class)
    @ConditionalOnMissingBean
    public RequestFingerprint requestFingerprint(
            @Value("${payorch.idempotency.fingerprint-secret:}") String secret) {
        return new RequestFingerprint(secret);
    }
}
