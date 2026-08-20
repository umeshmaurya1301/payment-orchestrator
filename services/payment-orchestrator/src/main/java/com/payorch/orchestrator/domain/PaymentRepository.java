package com.payorch.orchestrator.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * Payments whose outcome nobody knows, that are due to be asked about.
     * Phase 8a.
     *
     * <h2>The predicate is shaped for the index, not for readability</h2>
     *
     * <p>{@code state = ?} first and the range on {@code nextPollAt} second,
     * matching {@code idx_payment_unknown_poll (state, next_poll_at)}. Equality
     * column, then range column - phase 8's first implementation note applied to
     * the query that motivated it. Reversing the index would leave a range
     * traversal with no further narrowing available inside it, so every row in
     * the range would have to be examined to test the state.
     *
     * <p>{@code nextPollAt IS NULL} is the "never polled" case and means due
     * immediately. It is inside the same predicate rather than a separate query
     * because MySQL can satisfy {@code IS NULL} from the index - nulls sort
     * first in a B-tree - so one traversal serves both.
     *
     * <p>Ordered by {@code nextPollAt} so the longest-waiting payments are asked
     * about first. Without it, a batch limit under a large backlog would poll an
     * arbitrary subset forever and starve the rest - which is the failure mode
     * where a payment sits UNKNOWN for a week while the poller looks busy.
     *
     * <p><strong>{@code UNRESOLVED} is not selected, and that is what "the
     * poller gives up" means in practice.</strong> The state machine still
     * allows a human to resolve one; nothing automatic will pick it up again.
     */
    @Query("""
            select p from Payment p
             where p.state = com.payorch.orchestrator.domain.PaymentState.UNKNOWN
               and (p.nextPollAt is null or p.nextPollAt <= :now)
             order by p.nextPollAt asc nulls first
            """)
    List<Payment> findUnknownDueForPolling(@Param("now") Instant now, Limit limit);

    /** How many payments are currently unresolved. Phase 8a's alert reads this. */
    @Query("select count(p) from Payment p where p.state = :state")
    long countByState(@Param("state") PaymentState state);

    /**
     * When the oldest payment in this state was created.
     *
     * <p>AGE, not count, is the signal phase 8 asks to alert on. A steady
     * hundred UNKNOWNs that resolve within a minute is a healthy system under
     * load; three that have been sitting for an hour is a provider that has
     * stopped answering and a growing pile of money nobody can account for. The
     * count cannot tell those apart and the age can.
     */
    @Query("select min(p.createdAt) from Payment p where p.state = :state")
    Instant oldestInState(@Param("state") PaymentState state);
}
