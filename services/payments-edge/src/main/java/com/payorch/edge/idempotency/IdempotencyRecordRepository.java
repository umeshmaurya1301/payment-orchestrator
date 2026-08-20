package com.payorch.edge.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    void deleteByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    /**
     * Takes over a claim whose owner is presumed dead. Phase 7c.
     *
     * <h2>Why this is one conditional UPDATE and not a read then a write</h2>
     *
     * <p>Because two requests can arrive at an abandoned claim at the same
     * moment, and the whole purpose of the row is that only one of them may
     * proceed. Read-then-write gives both of them the same "yes, it is expired"
     * and both then run the work - reintroducing, at the recovery path, exactly
     * the double charge the unique constraint was put there to prevent. The
     * database decides, by counting rows affected: exactly one caller sees 1.
     *
     * <h2>The predicates are the safety argument</h2>
     *
     * <ul>
     *   <li>{@code responseStatus IS NULL} - a completed record is never taken
     *       over. It is an answer somebody may still be replaying.</li>
     *   <li>{@code claimExpiresAt IS NOT NULL} - a row written before this
     *       migration has no opinion about when its owner would be dead, and
     *       taking over a claim on no opinion is how a live request gets
     *       duplicated.</li>
     *   <li>{@code claimExpiresAt &lt; :now} - the owner has had longer than any
     *       request could legitimately take. See {@code V13__idempotency_ttl.sql}
     *       for why that bound is knowable rather than guessable.</li>
     * </ul>
     *
     * <p>The fingerprint is overwritten, deliberately: the new claimant owns the
     * key now, and leaving the dead request's fingerprint would make every
     * subsequent duplicate of the LIVE request look like a key reuse.
     *
     * @return 1 if this caller took the claim, 0 if somebody else did or it
     *         completed first
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IdempotencyRecord r
               set r.requestFingerprint = :fingerprint,
                   r.claimExpiresAt = :claimExpiresAt
             where r.merchantId = :merchantId
               and r.idempotencyKey = :key
               and r.responseStatus is null
               and r.claimExpiresAt is not null
               and r.claimExpiresAt < :now
            """)
    int takeOverExpiredClaim(@Param("merchantId") UUID merchantId,
                             @Param("key") String key,
                             @Param("fingerprint") String fingerprint,
                             @Param("claimExpiresAt") Instant claimExpiresAt,
                             @Param("now") Instant now);

    /**
     * Deletes completed records past their retention window, in bounded batches.
     *
     * <p><strong>Bounded, and that is not a detail.</strong> An unbounded
     * {@code DELETE} against a table every payment writes to holds row locks for
     * as long as it takes, and the first run after this ships has the whole
     * backlog to get through. A cleanup job whose first execution stalls the
     * payment path is worse than no cleanup job. The caller loops until a batch
     * comes back short.
     *
     * <p>Only rows with an {@code expires_at} are eligible. Records written
     * before phase 7c have none and are left alone rather than swept on a guess
     * - they are a finite, shrinking population, and deleting an answer somebody
     * is still entitled to replay is not worth doing on an assumption.
     */
    @Modifying
    @Query(value = """
            delete from idempotency_record
             where expires_at is not null
               and expires_at < :now
             limit :batchSize
            """, nativeQuery = true)
    int deleteExpiredBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
