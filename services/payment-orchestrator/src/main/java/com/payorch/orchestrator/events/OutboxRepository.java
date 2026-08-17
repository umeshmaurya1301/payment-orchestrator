package com.payorch.orchestrator.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * The relay's claim query: unpublished rows whose lease is free.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}, via the pessimistic write lock, so two
     * relay instances polling the same table step over each other's rows rather
     * than both publishing them.
     *
     * <p><strong>The transaction that runs this must not touch the network.</strong>
     * That is not a style preference: this scan takes next-key locks on the
     * `published_at IS NULL` range, which is exactly the gap every new outbox row
     * is inserted into, so holding it across a Kafka publish blocks the payment
     * path. Measured at four payments stranded in AUTHORIZING with
     * `Lock wait timeout exceeded` - see V10.
     *
     * @param leaseCutoff rows claimed before this are considered abandoned and
     *                    may be claimed again, so a relay that dies mid-publish
     *                    does not strand its batch
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT e FROM OutboxEvent e
            WHERE e.publishedAt IS NULL
              AND (e.claimedAt IS NULL OR e.claimedAt < :leaseCutoff)
            ORDER BY e.createdAt ASC
            """)
    List<OutboxEvent> claimUnpublished(@Param("leaseCutoff") Instant leaseCutoff, Limit limit);

    long countByPublishedAtIsNull();
}
