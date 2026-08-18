package com.payorch.ledger.backlog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The two numbers phase 6's last exit criterion asks for, as gauges.
 *
 * <pre>
 *   payorch.consumer.lag     events published and not yet committed by the ledger
 *   payorch.dlq.pending      dead-lettered records nobody has replayed
 *   payorch.dlq.records      everything the DLQ has ever held
 * </pre>
 *
 * <h2>Why {@code pending} and not {@code records}</h2>
 *
 * <p>"DLQ depth" is ambiguous in a log-structured queue and the ambiguity is
 * expensive. Records are not removed when they are handled - replaying moves a
 * consumer offset, it does not delete anything - so {@code records} rises forever
 * and never falls. A rule thresholding it fires during the first incident and
 * then <strong>stays firing for the life of the cluster</strong>, which trains
 * everyone to ignore it. {@code pending} returns to zero when somebody fixes the
 * problem, which is the entire property an alert needs.
 *
 * <p>Both are published anyway, because the ratio is the interesting thing during
 * an investigation: 12 pending out of 12 records is a new incident, 12 out of
 * 4,000 is a Tuesday.
 *
 * <h2>Gauges, and why that is not a contradiction of phase 4e</h2>
 *
 * <p>4e's finding was that a <em>cumulative counter</em> registered as a gauge is
 * summed rather than differenced by the query layer, producing rate graphs that
 * are wrong in a plausible-looking way - which is why every counter in this
 * service is a {@code FunctionCounter}. These three are not counters. A backlog
 * genuinely goes up and down, so its current value is the meaningful reading and
 * a gauge is the correct instrument. The lesson was about matching the
 * instrument to the quantity, not about avoiding gauges.
 *
 * <h2>Polled, not computed on scrape</h2>
 *
 * <p>Each reading is three round trips to the broker. Doing that inside the
 * gauge's supplier would put broker latency on the path of every
 * {@code /actuator/prometheus} scrape - including the ones SigNoz makes every
 * fifteen seconds and the one a health check makes during an outage, which is
 * exactly when the broker is slowest. So a scheduled task refreshes an
 * {@code AtomicLong} and the gauge reads that. The cost is that the value is up
 * to one interval stale, which is immaterial for a number whose alert window is
 * five minutes.
 */
public class BacklogMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(BacklogMetrics.class);

    private final KafkaBacklog backlog;
    private final String mainTopic;
    private final String group;
    private final String dlqTopic;
    private final String dlqGroup;

    /**
     * Phase 6j. How many accounts' cached balances disagree with their entries.
     *
     * <p>Polled here rather than computed per scrape because it is a query, and
     * it lives beside the backlog gauges because it answers the same kind of
     * question: something that should be zero and is not. Zero is the only
     * healthy value.
     */
    private final java.util.function.LongSupplier driftedAccounts;

    private final AtomicLong consumerLag = new AtomicLong(-1);
    private final AtomicLong dlqPending = new AtomicLong(-1);
    private final AtomicLong dlqRecords = new AtomicLong(-1);
    private final AtomicLong drifted = new AtomicLong(0);

    public BacklogMetrics(KafkaBacklog backlog, String mainTopic, String group,
                          String dlqTopic, String dlqGroup,
                          java.util.function.LongSupplier driftedAccounts) {
        this.backlog = backlog;
        this.driftedAccounts = driftedAccounts;
        this.mainTopic = mainTopic;
        this.group = group;
        this.dlqTopic = dlqTopic;
        this.dlqGroup = dlqGroup;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("payorch.consumer.lag", consumerLag, AtomicLong::get)
                .description("events on the main topic not yet committed by the ledger group")
                .tag("topic", mainTopic)
                .tag("group", group)
                .register(registry);

        Gauge.builder("payorch.dlq.pending", dlqPending, AtomicLong::get)
                .description("dead-lettered records nobody has replayed. Returns to zero; "
                        + "unlike payorch.dlq.records, which never falls")
                .tag("topic", dlqTopic)
                .register(registry);

        Gauge.builder("payorch.dlq.records", dlqRecords, AtomicLong::get)
                .description("every record the DLQ has ever held. Context, not an alert")
                .tag("topic", dlqTopic)
                .register(registry);

        Gauge.builder("payorch.ledger.drifted_accounts", drifted, AtomicLong::get)
                .description("accounts whose cached balance disagrees with their entries. "
                        + "Must be zero; non-zero is a lost update")
                .register(registry);
    }

    /**
     * 15 seconds, matching the OTLP export step. A slower poll would publish the
     * same value twice and make a backlog look flat while it climbed; a faster
     * one would be three broker round trips nobody reads.
     */
    @Scheduled(fixedDelayString = "${payorch.ledger.backlog-poll-ms:15000}")
    public void refresh() {
        KafkaBacklog.Backlog main = backlog.of(mainTopic, group);
        consumerLag.set(main.lag());

        KafkaBacklog.Backlog dlq = backlog.of(dlqTopic, dlqGroup);
        dlqPending.set(dlq.lag());
        dlqRecords.set(dlq.records());

        try {
            drifted.set(driftedAccounts.getAsLong());
        } catch (RuntimeException e) {
            // A database that cannot be queried is somebody else's alert. This
            // poller must not die on it, or the Kafka gauges above stop too.
            log.warn("could not read ledger drift: {}", e.toString());
        }
    }

    /** For {@code /actuator/ledger} and for a human during an incident. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("consumerLag", consumerLag.get());
        out.put("dlqPending", dlqPending.get());
        out.put("dlqRecords", dlqRecords.get());
        out.put("driftedAccounts", drifted.get());
        return out;
    }
}
