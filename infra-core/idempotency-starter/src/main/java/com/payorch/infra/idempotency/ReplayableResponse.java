package com.payorch.infra.idempotency;

/**
 * A response as it was actually sent, kept so it can be sent again.
 *
 * <p>The body is stored as <strong>rendered bytes</strong>, and that is the
 * whole point of this type. The obvious alternative - store the domain object,
 * re-serialize it on replay - does not produce the same output. Field ordering
 * depends on reflection order, timestamps re-render at the precision the
 * formatter happens to use that day, and a newly added field appears in the
 * replay but not in the original. A client that hashes or diffs the response
 * then sees two different answers to what is supposed to be the same request.
 *
 * <p>Storing bytes makes byte-identical replay a property of the design rather
 * than something to be hoped for and periodically re-verified.
 *
 * @param status      the HTTP status that was sent
 * @param contentType the {@code Content-Type} that was sent
 * @param body        the exact bytes that were written
 */
public record ReplayableResponse(int status, String contentType, byte[] body) {
}
