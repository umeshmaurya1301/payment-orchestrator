package com.payorch.orchestrator.routing;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;

import com.payorch.infra.observability.ProviderHealth;

/**
 * The orchestrator's cached view of how {@code psp-connector} rates each
 * provider.
 *
 * <p>Polled, not asked per payment. The shape is deliberately the same as 3f's
 * {@code ProviderConfigStore}, and for the same reasons:
 *
 * <ul>
 *   <li><strong>No synchronous hop on the payment path.</strong> Calling the
 *       connector to decide where to send a call to the connector would put a
 *       second network round trip in front of every authorization and make
 *       routing fail when the thing being routed to is unwell - which is exactly
 *       when routing matters most.</li>
 *   <li><strong>Fail-open to the last known good view.</strong> If the poll
 *       fails, the previous scores stay in force. They age, but stale health is
 *       enormously better than no health, and the alternative - falling back to
 *       static priority the moment a scrape times out - would undo phase 5
 *       precisely during an incident.</li>
 *   <li><strong>Neutral before the first successful poll.</strong> An empty map
 *       means the router falls back to priority order, which is phase 1's
 *       behaviour and a safe thing to be doing for the two seconds before the
 *       first response arrives.</li>
 * </ul>
 *
 * <p>The staleness is bounded and visible: {@link #ageMs} is published as a
 * metric, because a router quietly deciding on a five-minute-old picture is a
 * failure mode that looks exactly like a working one.
 */
public class ProviderHealthStore {

    private static final Logger log = LoggerFactory.getLogger(ProviderHealthStore.class);

    /**
     * How long a cached view may age before it is discarded entirely.
     *
     * <p>Not infinite. Fail-open means "keep using the last picture", and past
     * some point the last picture is a lie - a provider that was healthy ninety
     * seconds ago tells you nothing about now, and continuing to route on it is
     * worse than admitting ignorance and falling back to priority order.
     *
     * <p>30 s against a 2 s poll: fifteen consecutive failures. That is a
     * connector that is properly down rather than one that dropped a scrape.
     */
    private static final Duration MAX_AGE = Duration.ofSeconds(30);

    private final RestClient client;
    private final AtomicReference<Map<String, Integer>> scores =
            new AtomicReference<>(Map.of());
    private final AtomicLong lastSuccessAt = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong polls = new AtomicLong();

    public ProviderHealthStore(String connectorBaseUrl) {
        this.client = RestClient.builder().baseUrl(connectorBaseUrl).build();
    }

    /**
     * Every 2 s, matching the connector's own config poll so the two views of a
     * provider cannot drift by more than one interval.
     */
    @Scheduled(fixedDelayString = "${payorch.routing.health-poll-ms:2000}")
    public void poll() {
        polls.incrementAndGet();
        try {
            HealthResponse response = client.get()
                    .uri("/actuator/providerhealth")
                    .retrieve()
                    .body(HealthResponse.class);
            if (response == null || response.providers() == null) {
                return;
            }
            scores.set(response.providers().entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey, e -> e.getValue().score())));
            lastSuccessAt.set(System.currentTimeMillis());
        } catch (Exception e) {
            long count = failures.incrementAndGet();
            // Once, then only occasionally. A connector that is down produces one
            // of these every 2 s, and a log that scrolls is a log nobody reads
            // during the incident it is describing.
            if (count == 1 || count % 30 == 0) {
                log.warn("provider health poll failed ({} consecutive); routing on a view {}ms old",
                        count, ageMs(), e);
            }
        }
    }

    /**
     * Current scores, or an empty map when there is no usable view.
     *
     * <p>Empty is the router's signal to fall back to priority order. It happens
     * before the first poll and after {@link #MAX_AGE} of failures, and both are
     * cases where guessing would be worse than reverting to phase 1's rule.
     */
    public Map<String, Integer> scores() {
        if (lastSuccessAt.get() == 0 || ageMs() > MAX_AGE.toMillis()) {
            return Map.of();
        }
        return scores.get();
    }

    /** Milliseconds since the last successful poll; {@code -1} if there never was one. */
    public long ageMs() {
        long at = lastSuccessAt.get();
        return at == 0 ? -1 : System.currentTimeMillis() - at;
    }

    public long failures() {
        return failures.get();
    }

    public long polls() {
        return polls.get();
    }

    /** Only {@code score} is consumed; the rest of the payload is for humans. */
    record HealthResponse(Map<String, Entry> providers) {
        record Entry(int score, boolean routable, String reason) {
        }
    }

    /** Convenience for callers that want the constant rather than a magic number. */
    public static int unroutableThreshold() {
        return ProviderHealth.UNROUTABLE;
    }
}
