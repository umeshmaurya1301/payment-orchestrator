package com.payorch.ledger;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import com.payorch.ledger.backlog.BacklogMetrics;
import com.payorch.ledger.consume.PaymentEventConsumer;

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

    public LedgerEndpoint(PaymentEventConsumer consumer, BacklogMetrics backlog) {
        this.consumer = consumer;
        this.backlog = backlog;
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
        return out;
    }
}
