package com.payorch.infra.idempotency;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Placeholder for the idempotency machinery.
 *
 * <p>Phase 1 puts the basic form here: a unique constraint on
 * {@code (merchant_id, idempotency_key)} plus response replay. Phase 7 hardens
 * it into the version that survives concurrency - request-body fingerprinting
 * so key reuse with a changed payload is caught rather than silently replayed,
 * a Redis in-flight marker returning 409 while the first request is still
 * running, cached response replay, and TTL expiry.
 */
@AutoConfiguration
public class IdempotencyAutoConfiguration {
}
