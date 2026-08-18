package com.payorch.ledger.backlog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
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

    private final KafkaBacklog backlog;
    private final String mainTopic;
    private final String group;
    private final String dlqTopic;
    private final String dlqGroup;

    private final AtomicLong consumerLag = new AtomicLong(-1);
    private final AtomicLong dlqPending = new AtomicLong(-1);
    private final AtomicLong dlqRecords = new AtomicLong(-1);

    public BacklogMetrics(KafkaBacklog backlog, String mainTopic, String group,
                          String dlqTopic, String dlqGroup) {
        this.backlog = backlog;
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
    }

    /** For {@code /actuator/ledger} and for a human during an incident. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("consumerLag", consumerLag.get());
        out.put("dlqPending", dlqPending.get());
        out.put("dlqRecords", dlqRecords.get());
        return out;
    }
}
