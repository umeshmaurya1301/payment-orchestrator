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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A payment, and the only thing allowed to change its state.
 *
 * <p>{@link #transitionTo} is the single entry point, and it consults
 * {@link PaymentTransitions} every time. There is deliberately no
 * {@code setState}: if any caller could assign a state directly, the transition
 * table would be documentation rather than enforcement, and the first illegal
 * move would be found by a report months later instead of by an exception.
 *
 * <p>Note the absence of any field that could hold a card number. This entity
 * carries a vault token and the two fragments PCI-DSS 3.3 permits to be
 * displayed. The tokenization boundary is a property of the type, not a rule
 * someone has to remember while writing a mapper.
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "merchant_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID merchantId;

    /**
     * {@code STRING}, never {@code ORDINAL}. An ordinal column silently re-maps
     * every historical row the moment a value is inserted into the middle of the
     * enum, and nothing fails at the time.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private PaymentState state;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "card_token", nullable = false, length = 48)
    private String cardToken;

    @Column(name = "card_bin", nullable = false, length = 6)
    private String cardBin;

    @Column(name = "card_last4", nullable = false, length = 4)
    private String cardLast4;

    @Column(name = "psp_id", length = 32)
    private String pspId;

    @Column(name = "merchant_reference", length = 128)
    private String merchantReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic locking, present from the first migration.
     *
     * <p>It costs nothing until phase 7, when concurrent updates to one payment
     * become the thing under test. Adding it later would mean backfilling every
     * row and re-testing every write path, which is a strange thing to schedule
     * deliberately.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * How many times the UNKNOWN poller has asked about this payment. Phase 8a.
     *
     * <p>The bound on giving up. See {@code UnknownResolver} for why a bound has
     * to exist: a provider that has genuinely lost a payment will never start
     * answering, and polling it forever is the slow-motion outage phase 8's trap
     * list names.
     */
    @Column(name = "resolution_attempts", nullable = false)
    private int resolutionAttempts;

    /**
     * When this payment may next be polled. Null means now.
     *
     * <p>The scheduling decision is written at the moment it is MADE rather than
     * recomputed at query time, so "everything due now" is an indexed range
     * predicate instead of a filter that has to examine every candidate. See
     * {@code V14__unknown_resolution.sql}.
     */
    @Column(name = "next_poll_at")
    private Instant nextPollAt;

    protected Payment() {
        // for JPA
    }

    public static Payment initiate(UUID merchantId,
                                   long amountMinor,
                                   String currency,
                                   String cardToken,
                                   String cardBin,
                                   String cardLast4,
                                   String merchantReference) {
        Payment payment = new Payment();
        // Generated here rather than by the database. The identifier is needed
        // before the insert - it goes into log lines and into the attempt row -
        // and a client-generated time-ordered key gives that up nothing in
        // exchange for index locality.
        payment.id = Uuid7.generate();
        payment.merchantId = merchantId;
        payment.state = PaymentState.INITIATED;
        payment.amountMinor = amountMinor;
        payment.currency = currency;
        payment.cardToken = cardToken;
        payment.cardBin = cardBin;
        payment.cardLast4 = cardLast4;
        payment.merchantReference = merchantReference;
        return payment;
    }

    /**
     * @throws PaymentTransitions.IllegalTransitionException if the edge is not
     *         in the table
     */
    public void transitionTo(PaymentState next) {
        PaymentTransitions.check(state, next);
        this.state = next;
    }

    public void assignPsp(String pspId) {
        this.pspId = pspId;
    }

    @PrePersist
    void onInsert() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public PaymentState getState() {
        return state;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCardToken() {
        return cardToken;
    }

    public String getCardBin() {
        return cardBin;
    }

    public String getCardLast4() {
        return cardLast4;
    }

    public String getPspId() {
        return pspId;
    }

    public String getMerchantReference() {
        return merchantReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Records that the poller asked and did not get a usable answer. Phase 8a.
     *
     * <p>Increments the attempt count and schedules the next one. Deliberately
     * NOT a state change - the payment is still UNKNOWN, because nothing was
     * learned. Only {@link #transitionTo} moves a payment between states, and a
     * method that quietly did both would be the second way to change state that
     * {@link PaymentTransitions} exists to prevent.
     */
    public void recordPollAttempt(Instant nextAttemptAt) {
        this.resolutionAttempts++;
        this.nextPollAt = nextAttemptAt;
    }

    public int getResolutionAttempts() {
        return resolutionAttempts;
    }

    /** Null means the payment is due to be polled on the next tick. */
    public Instant getNextPollAt() {
        return nextPollAt;
    }

    public long getVersion() {
        return version;
    }
}
