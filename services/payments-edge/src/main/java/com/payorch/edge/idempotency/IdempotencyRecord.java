package com.payorch.edge.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.infra.persistence.Uuid7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One idempotency key, and the response that was sent for it.
 *
 * <p>The row is written when the key is claimed and updated when the work
 * finishes, so its lifetime has two phases: claimed-but-unanswered, and
 * complete. That distinction is what lets a duplicate arriving mid-flight be
 * told apart from a duplicate arriving afterwards.
 */
@Entity
@Table(name = "idempotency_record",
        // Declared here as well as in V2__core_tables.sql. The migration is what
        // creates it in MySQL, but stating it on the entity means any schema
        // generated from these mappings - a test, a future tool - carries the
        // constraint too. Without it, JpaIdempotencyStore.claim silently always
        // wins and idempotency degrades into no idempotency at all, which is
        // exactly the kind of failure that would only show up under load.
        uniqueConstraints = @UniqueConstraint(
                name = "uk_idempotency_merchant_key",
                columnNames = {"merchant_id", "idempotency_key"}))
public class IdempotencyRecord {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "merchant_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    private String idempotencyKey;

    /**
     * What the claiming request asked for. Phase 7a.
     *
     * <p>Nullable because rows written before 7a have none and cannot be
     * backfilled: the request bodies were never stored, deliberately, because
     * they contain card numbers. NULL means "cannot be compared", which
     * IdempotencyGuard handles explicitly rather than treating as a mismatch.
     *
     * <p>{@code updatable = false}. The fingerprint is a fact about the request
     * that claimed the key, and a claim whose fingerprint could be rewritten is
     * a claim that proves nothing.
     */
    @Column(name = "request_fingerprint", updatable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_content_type", length = 128)
    private String responseContentType;

    /**
     * The response exactly as it was written.
     *
     * <p>Bytes, not a serialized object to be re-rendered. Re-serializing does
     * not reproduce the original output - field order and timestamp formatting
     * drift, and a field added next month appears in the replay but not in the
     * original. Phase 1's exit criterion is byte-identical replay, and this
     * column is how that is achieved rather than hoped for.
     */
    @Column(name = "response_body", length = 8192)
    private byte[] responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When an unanswered claim may be taken over. Phase 7c.
     *
     * <p>Updatable, unlike the fingerprint, because taking over a claim moves
     * it: the new owner gets a fresh window, or the second taker would inherit
     * an already-expired one and a third could take it from them.
     *
     * <p>Null only on rows written before 7c, which are grandfathered as
     * never-expiring rather than retroactively declared dead - see
     * {@code IdempotencyRecordRepository.takeOverExpiredClaim}.
     */
    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * When the record stops being replayable and may be swept. Phase 7c.
     *
     * <p>Set at completion rather than at claim, because the window is about how
     * long an ANSWER stays available. A claim that is still running has not
     * produced one yet, and dating its retention from the moment it started
     * would shorten the window by however long the work took.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    protected IdempotencyRecord() {
        // for JPA
    }

    /** A claim with no response yet: this key is taken, the work is running. */
    public static IdempotencyRecord claim(UUID merchantId, String idempotencyKey,
                                          String requestFingerprint, Duration claimTtl) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.id = Uuid7.generate();
        record.merchantId = merchantId;
        record.idempotencyKey = idempotencyKey;
        record.requestFingerprint = requestFingerprint;
        record.claimExpiresAt = Instant.now().plus(claimTtl);
        return record;
    }

    public void complete(int status, String contentType, byte[] body, Duration retention) {
        this.responseStatus = status;
        this.responseContentType = contentType;
        this.responseBody = body;
        this.completedAt = Instant.now();
        this.expiresAt = this.completedAt.plus(retention);
        // Cleared, not left behind. A completed record is never taken over -
        // the repository predicate says so - but leaving a stale claim window on
        // it makes the row read as though it could be, and the next person to
        // touch this code has to re-derive that it cannot.
        this.claimExpiresAt = null;
    }

    public boolean isComplete() {
        return responseStatus != null && responseBody != null;
    }

    @PrePersist
    void onInsert() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** Null for a record written before phase 7a. */
    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseContentType() {
        return responseContentType;
    }

    public byte[] getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    /** Null once complete, and on rows written before phase 7c. */
    public Instant getClaimExpiresAt() {
        return claimExpiresAt;
    }

    /** Null until complete, and on rows written before phase 7c. */
    public Instant getExpiresAt() {
        return expiresAt;
    }
}
