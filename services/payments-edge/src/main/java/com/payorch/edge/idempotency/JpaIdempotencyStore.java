package com.payorch.edge.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.infra.idempotency.IdempotencyStore;
import org.infra.idempotency.ReplayableResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The durable half of idempotency, backed by MySQL.
 *
 * <p>The interesting method is {@link #claim}. It inserts and lets the unique
 * constraint on {@code (merchant_id, idempotency_key)} decide the winner, rather
 * than reading first and inserting second. The read-then-write version is the
 * one everybody writes, and it has a window between the two statements that two
 * concurrent requests both fit through - both find nothing, both proceed, and
 * the merchant is charged twice. Phase 7 measures exactly that window under k6
 * load; this code is written not to have it.
 *
 * <h2>Phase 7c: claims expire</h2>
 *
 * <p>A claim is written before the work runs and updated when it finishes. A
 * process that dies in between - SIGKILL, OOM, an evicted pod - leaves the row
 * claimed and unanswered, and before 7c nothing ever cleaned it up: that key was
 * unusable forever, which is the precise opposite of what an idempotency key is
 * for. A losing claim now checks whether the row it lost to has been abandoned,
 * and takes it over if so.
 */
@Component
public class JpaIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(JpaIdempotencyStore.class);

    private final IdempotencyRecordRepository records;

    /**
     * How long before an unanswered claim is presumed dead.
     *
     * <p><strong>Bounded below by the longest request that could still be
     * running, and that bound is not a preference.</strong> Taking over a claim
     * is deciding the first request is gone; decide that while it is merely slow
     * and both run, which calls the provider twice. Phase 3a put a hard ceiling
     * on request duration - {@code DEADLINE_MAX_BUDGET_MS}, 60 seconds, clamped
     * even for a caller who asks for more - so the floor is knowable rather than
     * guessable. The default is fifteen times it.
     */
    private final Duration claimTtl;

    /** How long a completed response stays replayable. */
    private final Duration retention;

    /**
     * A dedicated transaction template, not {@code @Transactional} on
     * {@link #claim}.
     *
     * <p>Two reasons, both about the constraint violation. First, catching a
     * {@code DataIntegrityViolationException} <em>inside</em> the transaction
     * that caused it is useless: the transaction is already marked rollback-only
     * and every subsequent statement fails. The catch has to sit outside the
     * boundary, which means the boundary cannot be the method. Second,
     * {@code REQUIRES_NEW} keeps the rollback contained, so a losing claim does
     * not poison a transaction the caller might have open.
     */
    private final TransactionTemplate requiresNew;

    public JpaIdempotencyStore(IdempotencyRecordRepository records,
                               PlatformTransactionManager transactionManager,
                               @Value("${payorch.idempotency.claim-ttl-ms:900000}") long claimTtlMs,
                               @Value("${payorch.idempotency.retention-ms:86400000}") long retentionMs) {
        this.records = records;
        this.claimTtl = Duration.ofMillis(claimTtlMs);
        this.retention = Duration.ofMillis(retentionMs);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean claim(UUID merchantId, String key, String fingerprint) {
        try {
            requiresNew.executeWithoutResult(status ->
                    // saveAndFlush, not save: the insert has to reach the
                    // database now so the constraint can reject it here, rather
                    // than at commit time when the caller has already left.
                    records.saveAndFlush(
                            IdempotencyRecord.claim(merchantId, key, fingerprint, claimTtl)));
            return true;
        } catch (DataIntegrityViolationException e) {
            // Somebody holds this key. Phase 7c: they may not be alive.
            return takeOverIfAbandoned(merchantId, key, fingerprint);
        }
    }

    /**
     * Claims a key whose previous owner never came back.
     *
     * <p><strong>One conditional UPDATE, and the count is the decision.</strong>
     * Reading the row, deciding it is expired, and then writing would let two
     * requests both read the same expired claim and both proceed - putting the
     * double charge back in, at the recovery path, after the unique constraint
     * had removed it from the normal one. The database picks one winner by
     * matching one row.
     *
     * <p>Logged at WARN when it fires. A taken-over claim means a request died
     * mid-flight without releasing, which is a real event: it is the payment
     * whose outcome nobody knows, and the count of them over a day says
     * something about how this service is being terminated.
     */
    private boolean takeOverIfAbandoned(UUID merchantId, String key, String fingerprint) {
        Integer taken = requiresNew.execute(status -> records.takeOverExpiredClaim(
                merchantId, key, fingerprint, Instant.now().plus(claimTtl), Instant.now()));

        if (taken != null && taken > 0) {
            log.warn("took over an abandoned idempotency claim - merchant {}, key length {}. "
                            + "The previous holder never completed and never released it.",
                    merchantId, key.length());
            return true;
        }
        return false;
    }

    /**
     * One read, answering both questions a repeat raises.
     *
     * <p>The fingerprint and the response come back together on purpose.
     * Reading them separately would let the record complete between the two, so
     * a request could be told its fingerprint matched and then that nothing was
     * stored - an in-flight answer for a request that had, by then, finished.
     */
    @Override
    public Optional<Existing> find(UUID merchantId, String key) {
        return requiresNew.execute(status ->
                records.findByMerchantIdAndIdempotencyKey(merchantId, key)
                        .map(record -> new Existing(
                                record.getRequestFingerprint(),
                                Optional.of(record)
                                        .filter(IdempotencyRecord::isComplete)
                                        .map(complete -> new ReplayableResponse(
                                                complete.getResponseStatus(),
                                                complete.getResponseContentType(),
                                                complete.getResponseBody())))));
    }

    @Override
    public void complete(UUID merchantId, String key, ReplayableResponse response) {
        requiresNew.executeWithoutResult(status ->
                records.findByMerchantIdAndIdempotencyKey(merchantId, key)
                        .ifPresent(record -> record.complete(
                                response.status(), response.contentType(), response.body(),
                                retention)));
    }

    @Override
    public void release(UUID merchantId, String key) {
        requiresNew.executeWithoutResult(status ->
                records.deleteByMerchantIdAndIdempotencyKey(merchantId, key));
    }
}
