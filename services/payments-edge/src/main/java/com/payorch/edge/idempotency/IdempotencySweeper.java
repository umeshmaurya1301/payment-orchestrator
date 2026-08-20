package com.payorch.edge.idempotency;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes idempotency records past their retention window. Phase 7c.
 *
 * <h2>Why the table needs sweeping at all</h2>
 *
 * <p>Every payment creation writes a row, and every row holds up to 8 KB of
 * stored response body. Nothing has ever deleted one. That is a table on the hot
 * path of the busiest endpoint in the system, growing without bound, whose index
 * is consulted on every request - and the failure mode is not an error, it is
 * everything gradually getting slower with no single cause to point at.
 *
 * <h2>Bounded batches, and why the first run is the dangerous one</h2>
 *
 * <p>An unbounded {@code DELETE} holds row locks for as long as it takes, and
 * the first execution after this ships has the entire accumulated backlog to
 * work through. A cleanup job whose debut stalls the payment path is worse than
 * no cleanup job, and it is worse in the specific way that gets cleanup jobs
 * switched off permanently after one incident.
 *
 * <p>So: a bounded batch, repeated until a batch comes back short, with a cap on
 * how many batches one run may do. The cap means a large backlog is cleared over
 * several runs rather than in one long transaction - slower, and it never holds
 * anything for more than a batch.
 *
 * <h2>Scheduled, unlike the ledger's balance repair</h2>
 *
 * <p>Phase 6j deliberately made {@code repairBalances} manual, because a repair
 * that runs by itself hides the bug that made it necessary. This is the opposite
 * case and the contrast is worth stating: expiry is not a symptom of anything. A
 * record reaching its retention window is the design working, not a fault being
 * papered over, and there is nothing for an operator to learn from being asked
 * to press a button every day.
 */
@Component
public class IdempotencySweeper {

    private static final Logger log = LoggerFactory.getLogger(IdempotencySweeper.class);

    private final IdempotencyRecordRepository records;
    private final int batchSize;
    private final int maxBatchesPerRun;

    /**
     * A {@link TransactionTemplate}, NOT {@code @Transactional} on the batch
     * method.
     *
     * <p>This is phase 6d's lesson, and it cost that phase an afternoon. Spring
     * implements {@code @Transactional} with a proxy, so an annotated method
     * called from inside its own class does not go through it: the annotation is
     * silently inert, no transaction is started, and nothing at all says so. The
     * outbox relay had exactly this shape - a scheduled method calling an
     * annotated one on {@code this} - and it looked correct for as long as
     * nobody checked.
     *
     * <p>{@link #sweep} calls its batch method on {@code this}. So the batch
     * gets its transaction from an object rather than from an annotation, where
     * it cannot quietly not be there.
     */
    private final TransactionTemplate perBatch;

    private final AtomicLong swept = new AtomicLong();

    public IdempotencySweeper(IdempotencyRecordRepository records,
                              PlatformTransactionManager transactionManager,
                              @Value("${payorch.idempotency.sweep-batch-size:500}") int batchSize,
                              @Value("${payorch.idempotency.sweep-max-batches:20}") int maxBatchesPerRun) {
        this.records = records;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
        this.perBatch = new TransactionTemplate(transactionManager);
        this.perBatch.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @return how many records this run deleted
     */
    @Scheduled(
            fixedDelayString = "${payorch.idempotency.sweep-interval-ms:300000}",
            // A long initial delay, on purpose. Startup is when the connection
            // pool is coldest and the JIT has compiled nothing, and a
            // maintenance job is the last thing that should be competing for a
            // connection with the first real requests.
            initialDelayString = "${payorch.idempotency.sweep-initial-delay-ms:60000}")
    public long sweep() {
        Instant now = Instant.now();
        long total = 0;

        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            int deleted = deleteBatch(now);
            total += deleted;
            // A short batch means the backlog is gone. Stopping here rather than
            // running the remaining iterations against an empty predicate keeps
            // the steady state at one query rather than twenty.
            if (deleted < batchSize) {
                break;
            }
        }

        if (total > 0) {
            swept.addAndGet(total);
            log.info("swept {} expired idempotency records ({} in total since start)",
                    total, swept.get());
        }
        return total;
    }

    /**
     * One batch, in its own transaction.
     *
     * <p>Deliberately not one transaction around the whole loop. That would hold
     * every deleted row's lock until the last batch committed, which is the
     * unbounded delete this method exists to avoid, reassembled out of bounded
     * pieces.
     */
    private int deleteBatch(Instant now) {
        Integer deleted = perBatch.execute(status -> records.deleteExpiredBatch(now, batchSize));
        return deleted == null ? 0 : deleted;
    }

    /** Records deleted since this instance started. */
    public long swept() {
        return swept.get();
    }
}
