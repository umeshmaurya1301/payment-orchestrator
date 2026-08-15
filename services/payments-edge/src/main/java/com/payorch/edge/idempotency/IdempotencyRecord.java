package com.payorch.edge.idempotency;

import java.time.Instant;
import java.util.UUID;

import com.payorch.infra.persistence.Uuid7;
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

    @Column(name = "completed_at")
    private Instant completedAt;

    protected IdempotencyRecord() {
        // for JPA
    }

    /** A claim with no response yet: this key is taken, the work is running. */
    public static IdempotencyRecord claim(UUID merchantId, String idempotencyKey) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.id = Uuid7.generate();
        record.merchantId = merchantId;
        record.idempotencyKey = idempotencyKey;
        return record;
    }

    public void complete(int status, String contentType, byte[] body) {
        this.responseStatus = status;
        this.responseContentType = contentType;
        this.responseBody = body;
        this.completedAt = Instant.now();
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
}
