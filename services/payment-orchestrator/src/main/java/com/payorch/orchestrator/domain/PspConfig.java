package com.payorch.orchestrator.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A configured provider.
 *
 * <p>Read-only from this service's point of view in phase 1 - rows arrive by
 * migration, and routing is a query. Phase 5 makes it interesting by scoring
 * providers on observed health; the {@code priority} column then stops being the
 * whole decision and becomes the tie-break.
 */
@Entity
@Table(name = "psp_config")
public class PspConfig {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "psp_id", nullable = false, length = 32)
    private String pspId;

    @Column(name = "display_name", nullable = false, length = 64)
    private String displayName;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "priority", nullable = false)
    private int priority;

    /**
     * What this provider charges, in basis points of the transaction amount.
     *
     * <p>Phase 5d, for the {@code CHEAPEST} routing strategy. Basis points
     * rather than a decimal rate: integer comparison with no rounding
     * surprises, and it is the unit acquirers quote in.
     */
    @Column(name = "cost_bps", nullable = false)
    private int costBps;

    /**
     * A comma-separated list, which is a compromise worth naming. A join table
     * would be the normalised answer; for a handful of providers each supporting
     * a handful of currencies, it would add a join to the hot routing path in
     * exchange for correctness nobody is at risk of losing. Revisit if this ever
     * needs to be queried by currency rather than filtered in memory.
     */
    @Column(name = "supported_currencies", nullable = false, length = 128)
    private String supportedCurrencies;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PspConfig() {
        // for JPA
    }

    public boolean supports(String currency) {
        return Arrays.stream(supportedCurrencies.split(","))
                .map(String::strip)
                .anyMatch(supported -> supported.equalsIgnoreCase(currency));
    }

    public UUID getId() {
        return id;
    }

    public String getPspId() {
        return pspId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getCostBps() {
        return costBps;
    }

    public int getPriority() {
        return priority;
    }

    public String getSupportedCurrencies() {
        return supportedCurrencies;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
