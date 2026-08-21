package com.payorch.edge.merchant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One API key belonging to one merchant. Phase 9b.
 *
 * <h2>Why a merchant has several of these</h2>
 *
 * <p>Until 9b a merchant had exactly one key, stored as a column. Replacing it
 * meant editing both sides at the same instant, so every honest rotation had a
 * window where requests failed — and a credential that cannot be rotated without
 * an outage is one that does not get rotated. Keys then live for years, which is
 * the exposure that hashing at rest does not address.
 *
 * <p>Three states, and the middle one is the whole feature:
 *
 * <pre>
 *   ACTIVE     accepted, and the key the merchant is told to use
 *   RETIRING   accepted, no longer advertised — the overlap window
 *   REVOKED    not accepted; the row is kept, because deleting the evidence
 *              that a key existed is the opposite of an audit trail
 * </pre>
 */
@Entity
@Table(name = "merchant_api_key")
public class MerchantApiKey {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "merchant_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "api_key_hash", nullable = false, length = 64, updatable = false)
    private String apiKeyHash;

    @Column(name = "label", nullable = false, length = 64)
    private String label;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected MerchantApiKey() {
        // for JPA
    }

    /**
     * Whether this key may authenticate a request, at {@code now}.
     *
     * <p>The expiry check is deliberately not left to a scheduled job that flips
     * {@code RETIRING} to {@code REVOKED}. If that job is not running — and it
     * is exactly the kind of job nobody notices has stopped — an overlap window
     * silently becomes permanent, which means twice as many live credentials as
     * anybody believes there are. Reading {@code expires_at} on every
     * authentication makes the window close whether or not anything is watching.
     */
    public boolean isUsableAt(Instant now) {
        if ("REVOKED".equals(status)) {
            return false;
        }
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            return false;
        }
        return "ACTIVE".equals(status) || "RETIRING".equals(status);
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public String getLabel() {
        return label;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
