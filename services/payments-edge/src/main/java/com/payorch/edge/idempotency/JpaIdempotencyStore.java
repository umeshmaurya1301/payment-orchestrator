package com.payorch.edge.idempotency;

import java.util.Optional;
import java.util.UUID;

import com.payorch.infra.idempotency.IdempotencyStore;
import com.payorch.infra.idempotency.ReplayableResponse;
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
 */
@Component
public class JpaIdempotencyStore implements IdempotencyStore {

    private final IdempotencyRecordRepository records;

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
                               PlatformTransactionManager transactionManager) {
        this.records = records;
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
                            IdempotencyRecord.claim(merchantId, key, fingerprint)));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
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
                                response.status(), response.contentType(), response.body())));
    }

    @Override
    public void release(UUID merchantId, String key) {
        requiresNew.executeWithoutResult(status ->
                records.deleteByMerchantIdAndIdempotencyKey(merchantId, key));
    }
}
