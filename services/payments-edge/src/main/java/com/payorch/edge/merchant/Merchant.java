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
 * A merchant, as far as authentication is concerned.
 *
 * <p>Read-only here. Merchants arrive by migration in phase 1; onboarding is not
 * part of this project's scope, and pretending otherwise would add a CRUD
 * surface nothing else in the design depends on.
 */
@Entity
@Table(name = "merchant")
public class Merchant {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /**
     * SHA-256 hex of the API key. The key itself is never stored, so a dump of
     * this table does not let anyone call the API.
     */
    @Column(name = "api_key_hash", nullable = false, length = 64)
    private String apiKeyHash;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Merchant() {
        // for JPA
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
