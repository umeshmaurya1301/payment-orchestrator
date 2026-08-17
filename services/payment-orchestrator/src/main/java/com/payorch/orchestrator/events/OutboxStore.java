package com.payorch.orchestrator.events;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The relay's three short transactions, in a bean of their own.
 *
 * <h2>Why this is not just methods on {@code OutboxRelay}</h2>
 *
 * <p>They were, and it would not have worked. Spring's {@code @Transactional} is
 * applied by a proxy, and a proxy is only involved when a call arrives from
 * <em>outside</em> the bean. {@code OutboxRelay.relay()} calling its own
 * {@code claim()} is a plain Java method call that never touches the proxy, so
 * every annotation on it is inert.
 *
 * <p>The consequence is worse than a missing lock. {@code markPublished} mutates
 * a managed entity and relies on the transaction committing to flush it; with no
 * transaction there is nothing to flush, so the row is never marked, the next
 * poll claims it again, and the relay republishes the same events forever while
 * looking perfectly healthy. At-least-once would have quietly become
 * at-least-once-per-poll.
 *
 * <p>Splitting the transactional work into a separate bean makes every call a
 * genuine cross-bean call, so the proxy is always in the path. Self-injection or
 * a {@code TransactionTemplate} would also work; a separate class is the version
 * that cannot be broken again by someone moving a method.
 */
@Component
public class OutboxStore {

    private final OutboxRepository outbox;

    public OutboxStore(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    /**
     * Takes a lease on a batch and commits, releasing every lock before anything
     * touches the network. See {@code V10__outbox_lease.sql} for what happened
     * when the locks were held across the publish.
     *
     * <p>Returns detached copies rather than managed entities, so nothing
     * downstream can touch a JPA proxy outside the transaction that loaded it.
     */
    @Transactional
    public List<OutboxRelay.Claimed> claim(Duration lease, int batchSize) {
        Instant cutoff = Instant.now().minus(lease);
        return outbox.claimUnpublished(cutoff, Limit.of(batchSize)).stream()
                .peek(OutboxEvent::claim)
                .map(e -> new OutboxRelay.Claimed(
                        e.getId(), e.getAggregateId().toString(), e.getPayload()))
                .toList();
    }

    /** Per event, so one slow publish cannot hold a lock behind the next. */
    @Transactional
    public void markPublished(UUID id) {
        outbox.findById(id).ifPresent(OutboxEvent::markPublished);
    }

    @Transactional
    public void markFailed(UUID id, String error) {
        outbox.findById(id).ifPresent(e -> e.markFailed(error));
    }

    @Transactional(readOnly = true)
    public long pending() {
        return outbox.countByPublishedAtIsNull();
    }
}
