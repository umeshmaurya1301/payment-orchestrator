package com.payorch.ledger.recon;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One line of a provider's settlement file: what the PROVIDER says happened.
 *
 * <h2>Why this is stored rather than streamed</h2>
 *
 * <p>Reconciliation is a comparison between two accounts of the same events, and
 * a comparison needs both sides addressable. Streaming the file and checking each
 * line against the journal answers one of the three questions — "is this
 * settlement line in our ledger" — and cannot answer the other two, because
 * finding what is <em>missing</em> from a stream requires having read all of it.
 *
 * <p>It also makes the run repeatable. A settlement file arrives once; a
 * reconciliation over it may need to run again after somebody fixes something,
 * and re-downloading yesterday's file from a provider is not always possible.
 *
 * <h2>The provider is the source of truth here, and only here</h2>
 *
 * <p>Everywhere else in this system the ledger is authoritative. In a settlement
 * comparison it is not: the provider moved the money and their file says what
 * they moved. A mismatch is not "the file is wrong", it is a question, and the
 * three classes in {@link ReconciliationJob} are three different questions.
 *
 * @param batchId  which file this line came from, so a re-ingest of the same
 *                 file can replace its lines rather than double-count them
 * @param paymentId the join key. A provider file in the real world carries the
 *                 provider's reference and a merchant reference, and mapping
 *                 those back to a payment is itself work; the simulated file
 *                 carries the payment id so this job measures reconciliation
 *                 rather than parsing.
 */
@Document(collection = "settlement_line")
public class SettlementLine {

    @Id
    private String id;

    @Indexed
    private String batchId;

    /**
     * Indexed, and it is the one index this job cannot do without.
     *
     * <p>Phase 8's trap list names it: a {@code $lookup} is a nested-loop join,
     * and an unindexed one over a large collection is a collection scan per
     * input document. With 28,668 journal entries that is 28,668 scans.
     */
    @Indexed
    private UUID paymentId;

    private String providerRef;
    private long amountMinor;
    private String currency;
    private String pspId;
    private Instant settledAt;

    protected SettlementLine() {
    }

    public static SettlementLine of(String batchId, UUID paymentId, String providerRef,
                                    long amountMinor, String currency, String pspId,
                                    Instant settledAt) {
        SettlementLine line = new SettlementLine();
        line.batchId = batchId;
        line.paymentId = paymentId;
        line.providerRef = providerRef;
        line.amountMinor = amountMinor;
        line.currency = currency;
        line.pspId = pspId;
        line.settledAt = settledAt;
        return line;
    }

    public String getId() {
        return id;
    }

    public String getBatchId() {
        return batchId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPspId() {
        return pspId;
    }

    public Instant getSettledAt() {
        return settledAt;
    }
}
