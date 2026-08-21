package com.payorch.ledger.recon;

import java.time.Duration;
import java.util.List;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.domain.Sort;

/**
 * Creates the Mongo indexes, and says out loud which ones it created.
 *
 * <h2>Why this class exists, which is not a good story</h2>
 *
 * <p>{@code JournalEntry} and {@code SettlementLine} have carried
 * {@code @Indexed} annotations since phases 6e and 8 — including
 * {@code @Indexed(unique = true)} on {@code JournalEntry.eventId}, written as
 * the guard against at-least-once redelivery. In phase 9c I asked the live
 * database what indexes it had:
 *
 * <pre>
 *   db.journal.getIndexes()          -&gt;  [ { _id: 1 } ]      47,452 documents
 *   db.settlement_line.getIndexes()  -&gt;  [ { _id: 1 } ]
 * </pre>
 *
 * <p><strong>Not one of them existed.</strong> Spring Data MongoDB has defaulted
 * {@code auto-index-creation} to false since 3.0, so every {@code @Indexed} in
 * this project has been decoration from the day it was written. The annotations
 * are kept — they document intent next to the field — but an index that exists
 * only as an annotation is worse than no index at all, because the code reads as
 * though somebody handled the problem.
 *
 * <h2>Explicit creation rather than flipping auto-index-creation on</h2>
 *
 * <p>Setting {@code spring.data.mongodb.auto-index-creation=true} is one line
 * and would have created these. It is rejected for the same reason Spring Data
 * turned it off: index creation triggered by whichever entity a scan happens to
 * find, at whatever moment a service starts, on a collection of unknown size, is
 * an operation nobody chose to run. On 47,452 documents it is instant; on a real
 * journal it is a foreground build during a deploy.
 *
 * <p>This class runs the same operations deliberately, logs each one, and — the
 * part that matters — <strong>logs the indexes that already exist</strong>, so
 * the "we have indexes" claim is checkable in a log line rather than in an
 * annotation.
 */
public class MongoIndexes implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexes.class);

    private final MongoTemplate mongo;
    private final Duration settlementRetention;

    public MongoIndexes(MongoTemplate mongo, Duration settlementRetention) {
        this.mongo = mongo;
        this.settlementRetention = settlementRetention;
    }

    @Override
    public void afterPropertiesSet() {
        IndexOperations journal = mongo.indexOps("journal");

        // The guard that never existed. Duplicate delivery is not hypothetical -
        // it is the documented behaviour of the pipeline feeding this collection.
        //
        // It has never fired, and that is luck rather than design: the consumer
        // checks MySQL first and only writes here when MySQL says the event is
        // new, so the ordering has been doing the deduplication. Two consumer
        // instances racing between that check and this write would produce a
        // duplicate that nothing catches.
        ensure(journal, new Index().on("eventId", Sort.Direction.ASC).unique().named("ux_journal_event"));
        ensure(journal, new Index().on("paymentId", Sort.Direction.ASC).named("ix_journal_payment"));
        ensure(journal, new Index().on("recordedAt", Sort.Direction.ASC).named("ix_journal_recorded"));

        IndexOperations settlement = mongo.indexOps("settlement_line");
        ensure(settlement, new Index().on("batchId", Sort.Direction.ASC).named("ix_settlement_batch"));

        // The one the reconciliation job cannot do without at scale. Phase 8's
        // trap list: a $lookup is a nested-loop join, and an unindexed one is a
        // collection scan PER INPUT DOCUMENT.
        ensure(settlement, new Index().on("paymentId", Sort.Direction.ASC).named("ix_settlement_payment"));

        // Phase 9c. Retention on raw provider input.
        //
        // expireAfter on a date field, which is the only shape Mongo's TTL
        // monitor understands: a document whose field is missing, null, or not a
        // date is not expired, it is SKIPPED - silently, forever. That is the
        // trap, and it is why ingestedAt is set in the factory method rather
        // than left to whoever constructs the object.
        ensure(settlement, new Index()
                .on("ingestedAt", Sort.Direction.ASC)
                .expire(settlementRetention)
                .named("ttl_settlement_ingested"));

        backfillIngestedAt();

        report("journal", journal);
        report("settlement_line", settlement);
    }

    /**
     * Gives the pre-9c lines a TTL field, so the retention policy can see them.
     *
     * <p>The first run of the retention drill found <strong>7 of 14</strong>
     * settlement lines with no {@code ingestedAt} — every line written before
     * this field existed. Mongo's TTL monitor does not expire those, does not
     * queue them and does not complain: a document whose indexed field is
     * missing is skipped, silently, forever. So a retention claim would have
     * been false for precisely the oldest data, which is the data most likely to
     * be the subject of the request that prompted the policy.
     *
     * <p><strong>The timestamp comes from the {@code _id}, not from the clock.</strong>
     * An ObjectId encodes the second it was generated, so {@code $toDate} on it
     * recovers when the document was actually inserted. Backfilling with
     * {@code now()} would have been one character shorter and would have granted
     * every historical line a fresh full retention period — turning a
     * data-retention fix into a data-retention extension, which is the opposite
     * of what it is for.
     */
    private void backfillIngestedAt() {
        long missing = mongo.getCollection("settlement_line")
                .countDocuments(new Document("ingestedAt", new Document("$exists", false)));
        if (missing == 0) {
            return;
        }

        mongo.getCollection("settlement_line").updateMany(
                new Document("ingestedAt", new Document("$exists", false)),
                List.of(new Document("$set",
                        new Document("ingestedAt", new Document("$toDate", "$_id")))));

        log.info("backfilled ingestedAt on {} settlement lines from their ObjectId timestamps - "
                + "without it those documents would never have expired", missing);
    }

    /**
     * Creating an index that already exists is a no-op in MongoDB — unless the
     * options differ, which is an error rather than a replacement. Caught and
     * logged rather than thrown: a retention value changed in configuration
     * must not stop the service from starting, and the log line says exactly
     * what has to be dropped by hand.
     */
    private void ensure(IndexOperations ops, Index index) {
        try {
            ops.createIndex(index);
        } catch (RuntimeException e) {
            log.warn("could not create index {} - it may exist with different options: {}",
                    index.getIndexKeys(), e.getMessage());
        }
    }

    private void report(String collection, IndexOperations ops) {
        List<IndexInfo> existing = ops.getIndexInfo();
        log.info("mongo indexes on {}: {}", collection,
                existing.stream().map(IndexInfo::getName).toList());
    }

    /** Exposed for the retention drill, which needs to read the TTL back. */
    public Document ttlSpecification() {
        return mongo.getCollection("settlement_line")
                .listIndexes(Document.class)
                .into(new java.util.ArrayList<>())
                .stream()
                .filter(d -> d.containsKey("expireAfterSeconds"))
                .findFirst()
                .orElse(null);
    }
}
