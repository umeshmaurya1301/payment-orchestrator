package com.payorch.ledger.recon;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import com.payorch.infra.logging.LogEvent;
import com.payorch.infra.logging.LogFields;

/**
 * Compares what the provider says happened with what this ledger recorded.
 *
 * <h2>Three classes, and they are three different questions</h2>
 *
 * <pre>
 *   LEDGER_NOT_SETTLED     we posted it, the provider's file does not mention it
 *   SETTLED_NOT_IN_LEDGER  the provider moved money we have no record of
 *   AMOUNT_MISMATCH        both agree it happened and disagree how much
 * </pre>
 *
 * <p>They are not severities of one thing. The first is usually timing — a
 * capture late in the day settles tomorrow — and becomes interesting only when
 * it persists. The third is usually explainable: fees, FX, or a partial capture.
 *
 * <p><strong>The second is the one this job exists for.</strong> Money left a
 * cardholder and nothing in this system knows. It is exactly the failure phase
 * 5's failover nuance warns about — a request that was sent, answered nothing,
 * failed over to a second provider, and was charged twice — and it is invisible
 * to every check the ledger can run on itself. Experiment 15 measured that: both
 * of this system's invariants stay green while the books are wrong about the
 * world, because an invariant over our own tables cannot see a disagreement with
 * a third party. Reconciliation is the only thing that can.
 *
 * <h2>Why the join is indexed and why that is not a detail</h2>
 *
 * <p>Phase 8's trap list: {@code $lookup} is a nested-loop join. Without an index
 * on the foreign field it is a collection scan <em>per input document</em>, so a
 * report over 28,668 journal entries is 28,668 scans of the settlement
 * collection. {@link SettlementLine#getPaymentId()} is indexed for exactly this,
 * and the job records its own duration so that a regression shows up as a number
 * rather than as a job that quietly takes all night.
 */
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    /** Only postings count. A FAILED or UNKNOWN payment has no money to settle. */
    private static final String POSTED = "POSTED";

    private final MongoTemplate mongo;

    public ReconciliationJob(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** Replaces any previous lines for this batch, so a re-ingest is not a double count. */
    public int ingest(String batchId, List<SettlementLine> lines) {
        mongo.remove(new Query(Criteria.where("batchId").is(batchId)), SettlementLine.class);
        if (lines.isEmpty()) {
            return 0;
        }
        mongo.insert(lines, SettlementLine.class);
        log.info("settlement batch ingested",
                LogEvent.event()
                        .with(LogFields.OPERATION, "recon.ingest")
                        .with(LogFields.OUTCOME, batchId)
                        .args());
        return lines.size();
    }

    /**
     * The report.
     *
     * <p>Three aggregations rather than one clever pipeline. A single pipeline
     * producing all three classes is possible and would be write-only: each class
     * needs a different join direction and a different emptiness test, and fusing
     * them costs the ability to explain any one of them to somebody at 3am.
     */
    public Map<String, Object> run(String batchId) {
        Instant startedAt = Instant.now();

        List<Map<String, Object>> settledNotInLedger = settledNotInLedger(batchId);
        List<Map<String, Object>> ledgerNotSettled = ledgerNotSettled(batchId);
        List<Map<String, Object>> amountMismatch = amountMismatch(batchId);

        long tookMs = java.time.Duration.between(startedAt, Instant.now()).toMillis();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("batchId", batchId);
        report.put("settlementLines",
                mongo.count(new Query(Criteria.where("batchId").is(batchId)), SettlementLine.class));
        report.put("journalPostings",
                mongo.count(new Query(Criteria.where("disposition").is(POSTED)), "journal"));
        report.put("tookMs", tookMs);

        Map<String, Object> classes = new LinkedHashMap<>();
        classes.put("SETTLED_NOT_IN_LEDGER", summarise(settledNotInLedger));
        classes.put("LEDGER_NOT_SETTLED", summarise(ledgerNotSettled));
        classes.put("AMOUNT_MISMATCH", summarise(amountMismatch));
        report.put("mismatches", classes);

        long total = settledNotInLedger.size() + ledgerNotSettled.size() + amountMismatch.size();
        report.put("totalMismatches", total);

        // WARN when there is anything at all, because the whole point of the job
        // is that a clean run is the normal one. INFO would file a double charge
        // beside the startup banner.
        if (total > 0) {
            log.warn("reconciliation found mismatches",
                    LogEvent.event()
                            .with(LogFields.OPERATION, "recon.run")
                            .with(LogFields.OUTCOME, "MISMATCH")
                            .with(LogFields.AMOUNT_MINOR, total)
                            .with(LogFields.LATENCY_MS, tookMs)
                            .args());
        }
        return report;
    }

    private Map<String, Object> summarise(List<Map<String, Object>> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", rows.size());
        // A sample, not the lot. A report that inlines ten thousand mismatches is
        // a report nobody opens twice.
        out.put("sample", rows.stream().limit(5).toList());
        return out;
    }

    /**
     * THE DOUBLE-CHARGE CLASS. Settlement lines with no posted journal entry.
     *
     * <p>Driven from the settlement side, because that is the side that has the
     * rows we do not know about. Asking the journal would never surface them.
     */
    private List<Map<String, Object>> settledNotInLedger(String batchId) {
        List<Document> pipeline = List.of(
                new Document("$match", new Document("batchId", batchId)),
                new Document("$lookup", new Document()
                        .append("from", "journal")
                        .append("localField", "paymentId")
                        .append("foreignField", "paymentId")
                        .append("as", "posted")),
                new Document("$addFields", new Document("posted",
                        new Document("$filter", new Document()
                                .append("input", "$posted")
                                .append("as", "j")
                                .append("cond", new Document("$eq",
                                        List.of("$$j.disposition", POSTED)))))),
                new Document("$match", new Document("posted", new Document("$size", 0))),
                new Document("$project", new Document()
                        .append("_id", 0)
                        .append("paymentId", 1)
                        .append("providerRef", 1)
                        .append("amountMinor", 1)
                        .append("pspId", 1)));
        return rows("settlement_line", pipeline);
    }

    /** We posted it; the provider's file does not mention it. Usually timing. */
    private List<Map<String, Object>> ledgerNotSettled(String batchId) {
        List<Document> pipeline = List.of(
                new Document("$match", new Document("disposition", POSTED)),
                new Document("$lookup", new Document()
                        .append("from", "settlement_line")
                        .append("let", new Document("pid", "$paymentId"))
                        .append("pipeline", List.of(
                                new Document("$match", new Document("$expr",
                                        new Document("$and", List.of(
                                                new Document("$eq", List.of("$paymentId", "$$pid")),
                                                new Document("$eq", List.of("$batchId", batchId))))))))
                        .append("as", "settled")),
                new Document("$match", new Document("settled", new Document("$size", 0))),
                new Document("$project", new Document()
                        .append("_id", 0)
                        .append("paymentId", 1)
                        .append("amountMinor", 1)
                        .append("paymentState", 1)
                        .append("pspId", 1)),
                // Bounded. This class is dominated by ordinary timing - every
                // payment captured after the file was cut appears here - so an
                // unbounded projection would return most of the journal and the
                // interesting classes would be lost in it.
                new Document("$limit", 500));
        return rows("journal", pipeline);
    }

    /** Both sides agree it happened and disagree how much. Fees, FX, partial capture. */
    private List<Map<String, Object>> amountMismatch(String batchId) {
        List<Document> pipeline = List.of(
                new Document("$match", new Document("batchId", batchId)),
                new Document("$lookup", new Document()
                        .append("from", "journal")
                        .append("localField", "paymentId")
                        .append("foreignField", "paymentId")
                        .append("as", "posted")),
                new Document("$addFields", new Document("posted",
                        new Document("$filter", new Document()
                                .append("input", "$posted")
                                .append("as", "j")
                                .append("cond", new Document("$eq",
                                        List.of("$$j.disposition", POSTED)))))),
                new Document("$match", new Document("posted.0", new Document("$exists", true))),
                new Document("$addFields", new Document("ledgerAmount",
                        new Document("$arrayElemAt", List.of("$posted.amountMinor", 0)))),
                new Document("$match", new Document("$expr",
                        new Document("$ne", List.of("$amountMinor", "$ledgerAmount")))),
                new Document("$project", new Document()
                        .append("_id", 0)
                        .append("paymentId", 1)
                        .append("providerRef", 1)
                        .append("settledAmount", "$amountMinor")
                        .append("ledgerAmount", "$ledgerAmount")));
        return rows("settlement_line", pipeline);
    }

    /**
     * Runs one pipeline and copies the documents out.
     *
     * <p>{@code allowDiskUse} because the journal side of these joins is the
     * whole postings history and the aggregation must not fail with
     * "Sort exceeded memory limit" the first time it grows past 100MB - which is
     * a failure that arrives silently in a scheduled job.
     */
    private List<Map<String, Object>> rows(String collection, List<Document> pipeline) {
        List<Map<String, Object>> out = new ArrayList<>();
        mongo.getCollection(collection)
                .aggregate(pipeline)
                .allowDiskUse(true)
                .forEach((Document doc) -> out.add(new LinkedHashMap<>(doc)));
        return out;
    }
}
