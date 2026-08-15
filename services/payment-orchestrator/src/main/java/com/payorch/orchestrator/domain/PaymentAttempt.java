package com.payorch.orchestrator.domain;

import java.time.Instant;
import java.util.UUID;

import com.payorch.infra.persistence.Uuid7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One call to one provider.
 *
 * <p>A payment has a state; an attempt has an outcome. Keeping them separate is
 * what makes "this payment is AUTHORIZED after two attempts, the first of which
 * we never got an answer to" expressible - and that sentence is the whole
 * subject of phases 3, 5 and 8.
 *
 * <p>The attempt id is what goes to the provider as its idempotency reference,
 * which is why it is generated before the call rather than after it.
 */
@Entity
@Table(name = "payment_attempt",
        // Mirrors V2__core_tables.sql. A retry that reuses an attempt number is
        // a bug in the retry loop, and this is what makes it fail at the
        // database rather than quietly produce two rows that look like two
        // charges. Stated on the entity so any schema generated from these
        // mappings enforces it too.
        uniqueConstraints = @UniqueConstraint(
                name = "uk_attempt_payment_operation_no",
                columnNames = {"payment_id", "operation", "attempt_no"}))
public class PaymentAttempt {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "payment_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @Column(name = "psp_id", nullable = false, length = 32)
    private String pspId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 16)
    private Operation operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private Outcome outcome;

    @Column(name = "provider_ref", length = 64)
    private String providerRef;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum Operation {
        AUTHORIZE,
        CAPTURE,
        STATUS
    }

    /**
     * The same three-way split as the payment state machine, for the same
     * reason: {@code UNKNOWN} is not a flavour of {@code FAILED}.
     */
    public enum Outcome {
        /** Still in flight. The row exists before the call so a crash leaves a trace. */
        PENDING,
        SUCCESS,
        FAILED,
        UNKNOWN
    }

    protected PaymentAttempt() {
        // for JPA
    }

    /**
     * Creates the row <em>before</em> the provider is called.
     *
     * <p>Writing it afterwards would be simpler and would lose the case that
     * matters: a process that dies mid-authorization leaves no record that a
     * call was ever made, and reconciliation in phase 8 has nothing to reconcile
     * against.
     */
    public static PaymentAttempt start(UUID paymentId, int attemptNo, String pspId, Operation operation) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.id = Uuid7.generate();
        attempt.paymentId = paymentId;
        attempt.attemptNo = attemptNo;
        attempt.pspId = pspId;
        attempt.operation = operation;
        attempt.outcome = Outcome.PENDING;
        return attempt;
    }

    public void succeeded(String providerRef, long latencyMs) {
        this.outcome = Outcome.SUCCESS;
        this.providerRef = providerRef;
        this.latencyMs = (int) latencyMs;
    }

    public void failed(String providerRef, String errorCode, long latencyMs) {
        this.outcome = Outcome.FAILED;
        this.providerRef = providerRef;
        this.errorCode = errorCode;
        this.latencyMs = (int) latencyMs;
    }

    /** No answer arrived. The provider may or may not have charged the card. */
    public void unknown(String errorCode, long latencyMs) {
        this.outcome = Outcome.UNKNOWN;
        this.errorCode = errorCode;
        this.latencyMs = (int) latencyMs;
    }

    @PrePersist
    void onInsert() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public String getPspId() {
        return pspId;
    }

    public Operation getOperation() {
        return operation;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
