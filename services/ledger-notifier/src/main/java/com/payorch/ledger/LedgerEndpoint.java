package com.payorch.ledger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import com.payorch.ledger.backlog.BacklogMetrics;
import com.payorch.ledger.consume.PaymentEventConsumer;
import com.payorch.ledger.domain.AccountRepository;
import com.payorch.ledger.domain.LedgerPosting;

/**
 * What the ledger is doing, in one request.
 *
 * <h2>Why this exists now and not in phase 0</h2>
 *
 * <p>{@code application.yml} has listed {@code ledger} in
 * {@code management.endpoints.web.exposure.include} since phase 0, and there has
 * never been an endpoint with that id. {@code /actuator/ledger} returned a 404
 * while the configuration read as though it were there - the same shape as the
 * missing Prometheus registry found in 6f, where the exposure list named an
 * endpoint that no bean contributed and the only symptom was a URL that did not
 * answer.
 *
 * <p>Harmless on its own. Worth closing because "the config mentions it" is the
 * exact evidence that stopped anyone checking the Prometheus endpoint for four
 * phases.
 *
 * <h2>What it is for</h2>
 *
 * <p>The same numbers are in {@code /actuator/prometheus} and in SigNoz, and
 * that is the right place for graphs and alerts. This is for the first ninety
 * seconds of an incident, on a laptop, over SSH, when the question is "is the
 * ledger consuming, is it behind, and is anything stuck" and opening a browser
 * is not the answer.
 */
@Component
@Endpoint(id = "ledger")
public class LedgerEndpoint {

    private final PaymentEventConsumer consumer;
    private final BacklogMetrics backlog;
    private final LedgerPosting ledger;

    public LedgerEndpoint(PaymentEventConsumer consumer, BacklogMetrics backlog,
                          LedgerPosting ledger) {
        this.consumer = consumer;
        this.backlog = backlog;
        this.ledger = ledger;
    }

    @ReadOperation
    public Map<String, Object> state() {
        Map<String, Object> consumed = new LinkedHashMap<>();
        consumed.put("consumed", consumer.consumed());
        consumed.put("duplicates", consumer.duplicates());
        consumed.put("retried", consumer.retried());
        consumed.put("deadLettered", consumer.deadLettered());
        // Must be zero. A non-zero value means the dead-letter handler's own
        // logging is failing, which is how four messages became 10,306 DLQ
        // records in phase 6f.
        consumed.put("dltLogFailures", consumer.dltLogFailures());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("consumer", consumed);
        // -1 rather than 0 when the broker could not be reached. A backlog
        // reading of zero is the most dangerous thing to say about a cluster
        // you cannot talk to.
        out.put("backlog", backlog.snapshot());

        // Both numbers, because they answer different questions and only one of
        // them was being asked before phase 6j.
        //
        //   imbalance  SUM over every entry. Zero means the double-entry
        //              invariant holds - and it held throughout the incident
        //              below, because the entries were never wrong.
        //   drift      cached balance minus the sum of that account's entries.
        //              Non-zero means a lost update: the ledger's own denormalized
        //              totals disagree with the ledger. Measured at 1,911,000
        //              minor units on one account before applyDelta replaced
        //              read-modify-write.
        Map<String, Object> books = new LinkedHashMap<>();
        books.put("imbalance", ledger.imbalance());
        List<AccountRepository.Drift> drifted = ledger.drift();
        books.put("driftedAccounts", drifted.size());
        books.put("drift", drifted.stream()
                .map(d -> Map.of("account", d.accountRef(),
                        "cached", d.cached(),
                        "entries", d.actual(),
                        "delta", d.delta()))
                .toList());
        out.put("books", books);
        return out;
    }

    /**
     * Rewrites every cached balance from the entries.
     *
     * <p>A write operation, so it is a POST and cannot happen by refreshing a
     * page. Deliberately manual - see {@code LedgerPosting.repairBalances} for
     * why a repair that runs by itself is worse than one somebody has to
     * decide to run.
     */
    @WriteOperation
    public Map<String, Object> repair() {
        int before = ledger.drift().size();
        int repaired = ledger.repairBalances();
        return Map.of("driftedBefore", before,
                "repaired", repaired,
                "driftedAfter", ledger.drift().size());
    }
}
