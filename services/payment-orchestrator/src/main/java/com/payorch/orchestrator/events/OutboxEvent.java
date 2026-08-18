package com.payorch.orchestrator.events;

import java.time.Instant;
import java.util.UUID;

import com.payorch.infra.persistence.Uuid7;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One event owed to the outside world.
 *
 * <p>Written in the same transaction as the payment state change it describes -
 * see {@link OutboxWriter}, and the note there about why writing it anywhere
 * else makes the whole exercise pointless.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @Column(name = "id", columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "aggregate_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    // columnDefinition rather than a length: the migration's column is TEXT, and
    // under `ddl-auto: validate` the entity has to agree with it. It also fixes
    // the test schema, which Hibernate generates - the default mapping gave
    // VARCHAR(255) and every event over 255 bytes failed to insert, which is all
    // of them.
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    /**
     * The W3C trace context of the request that created this event, captured
     * inside its transaction. NULL when nothing was being traced, and for every
     * row written before V11. See that migration for why this is a column.
     */
    @Column(name = "traceparent", length = 64)
    private String traceparent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** When a relay last took a lease on this row. See V10 for why a lease. */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxEvent() {
    }

    public static OutboxEvent of(UUID aggregateId, String eventType, String payload) {
        return of(aggregateId, eventType, payload, null);
    }

    public static OutboxEvent of(UUID aggregateId, String eventType, String payload,
                                 String traceparent) {
        OutboxEvent event = new OutboxEvent();
        event.id = Uuid7.generate();
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.payload = payload;
        event.traceparent = traceparent;
        event.createdAt = Instant.now();
        event.attempts = 0;
        return event;
    }

    public void claim() {
        this.claimedAt = Instant.now();
    }

    /** Called by the relay once Kafka has acknowledged the write. */
    public void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    /**
     * Called when a publish attempt fails.
     *
     * <p>The row stays unpublished, so it is retried. {@code attempts} exists so
     * a row that can never be published is visible as a number rather than as an
     * unexplained backlog - a poison message at the head of the queue looks
     * exactly like a healthy queue that is simply behind.
     */
    public void markFailed(String error) {
        // Release the lease. Without this the row stays claimed until the lease
        // expires, so a publish that failed in one second would not be retried
        // for another sixty - the lease exists to survive a DEAD relay, not to
        // delay a live one that simply could not reach the broker.
        this.claimedAt = null;
        this.attempts++;
        this.lastError = error == null ? null
                : error.substring(0, Math.min(error.length(), 255));
    }

    public UUID getId() {
        return id;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public String getTraceparent() {
        return traceparent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getAttempts() {
        return attempts;
    }
}
