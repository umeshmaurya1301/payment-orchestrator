package com.payorch.connector.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import org.infra.resilience.breaker.CircuitBreakers;
import org.infra.resilience.bulkhead.Bulkhead;
import org.infra.resilience.bulkhead.SemaphoreBulkhead;
import org.infra.resilience.ratelimit.RateLimiter;
import org.infra.resilience.ratelimit.RedisTokenBucketRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Reads {@code psp_config} on a timer and pushes any change into the live
 * resilience components.
 *
 * <h2>Polling, not listening</h2>
 *
 * <p>MySQL has no change feed a client can subscribe to, so the honest choices
 * are a poll or an explicit "reload" endpoint. A poll wins because the endpoint
 * has to be called by something, and that something is the part that gets
 * forgotten at 3am. Two seconds of latency on a configuration change is
 * irrelevant next to the guarantee that a change made in a mysql shell arrives
 * without anyone remembering a second step.
 *
 * <p>The cost is one indexed read of a handful of rows every two seconds, on a
 * connection pool of two. Compared against a restart - the alternative way to
 * change these values - it is not close.
 *
 * <h2>Every poll re-reads; the fields decide the action</h2>
 *
 * <p>There is no version column and no change feed to trust. Each poll reads
 * every row and {@link ProviderConfig#sameBehaviourAs} decides whether anything
 * this service acts on actually moved. That is deliberately stricter than a
 * timestamp: rebuilding a circuit breaker discards its sliding window, so an
 * edit to {@code display_name} must not cost a provider its failure history -
 * and a poller that reset breakers every two seconds would leave them
 * permanently unable to accumulate enough calls to open.
 *
 * <h2>Failure policy</h2>
 *
 * <p>A failed poll keeps the last known configuration and logs. The alternative -
 * falling back to defaults, or disabling providers we cannot currently read -
 * would turn a database blip into a change of every limit in the system, which
 * is a far worse outcome than running two seconds stale. Configuration that
 * cannot be refreshed is still configuration.
 */
public class ProviderConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ProviderConfigStore.class);

    private static final String SELECT = """
            SELECT psp_id, display_name, base_url, enabled, priority,
                   deadline_slice_ms, retry_max_attempts,
                   breaker_failure_rate_threshold, breaker_window_seconds,
                   breaker_minimum_calls, breaker_wait_open_seconds,
                   breaker_half_open_permits,
                   bulkhead_max_concurrent, bulkhead_max_wait_ms,
                   egress_tps, updated_at
            FROM psp_config
            """;

    private final JdbcTemplate jdbc;
    private final CircuitBreakers breakers;
    private final Bulkhead bulkhead;
    private final RateLimiter egressLimiter;

    private final Map<String, ProviderConfig> current = new ConcurrentHashMap<>();
    private final AtomicLong lastAppliedAtMs = new AtomicLong();
    private final LongAdder reloads = new LongAdder();
    private final LongAdder changesApplied = new LongAdder();
    private final LongAdder failures = new LongAdder();

    public ProviderConfigStore(JdbcTemplate jdbc, CircuitBreakers breakers,
                               Bulkhead bulkhead, RateLimiter egressLimiter) {
        this.jdbc = jdbc;
        this.breakers = breakers;
        this.egressLimiter = egressLimiter;
        this.bulkhead = bulkhead;
    }

    /**
     * Loaded once, synchronously, before the service reports healthy.
     *
     * <p>Starting up and serving requests on hard-coded defaults until the first
     * poll fires would mean the first two seconds of every deploy ran on
     * configuration nobody chose - and a deploy under load is exactly when that
     * matters. If this throws, the context fails to start, which is correct: a
     * connector that cannot read its providers has nothing to connect to.
     */
    public void loadOrFail() {
        List<ProviderConfig> loaded = read();
        loaded.forEach(this::applyIfChanged);
        log.info("loaded {} provider(s) from psp_config: {}", loaded.size(),
                loaded.stream().map(ProviderConfig::pspId).sorted().toList());
    }

    @Scheduled(fixedDelayString = "${payorch.psp.config-poll-ms:2000}")
    public void poll() {
        try {
            List<ProviderConfig> loaded = read();
            reloads.increment();
            loaded.forEach(this::applyIfChanged);
            forgetProvidersRemovedFrom(loaded);
        } catch (RuntimeException e) {
            // Keep the last known config. See the class javadoc: a database blip
            // must not become a system-wide change of every limit.
            failures.increment();
            log.warn("psp_config reload failed, keeping the configuration already in effect: {}",
                    e.toString());
        }
    }

    private List<ProviderConfig> read() {
        return jdbc.query(SELECT, (rs, row) -> new ProviderConfig(
                rs.getString("psp_id"),
                rs.getString("display_name"),
                rs.getString("base_url"),
                rs.getBoolean("enabled"),
                rs.getInt("priority"),
                rs.getLong("deadline_slice_ms"),
                rs.getInt("retry_max_attempts"),
                rs.getInt("breaker_failure_rate_threshold"),
                rs.getInt("breaker_window_seconds"),
                rs.getInt("breaker_minimum_calls"),
                rs.getInt("breaker_wait_open_seconds"),
                rs.getInt("breaker_half_open_permits"),
                rs.getInt("bulkhead_max_concurrent"),
                rs.getLong("bulkhead_max_wait_ms"),
                rs.getInt("egress_tps"),
                rs.getTimestamp("updated_at").toInstant()));
    }

    private void applyIfChanged(ProviderConfig loaded) {
        ProviderConfig previous = current.get(loaded.pspId());
        if (loaded.sameBehaviourAs(previous)) {
            // Still store it: updated_at and display_name may have moved, and
            // the actuator endpoint reports what the database says.
            current.put(loaded.pspId(), loaded);
            return;
        }

        current.put(loaded.pspId(), loaded);
        push(loaded);
        changesApplied.increment();
        lastAppliedAtMs.set(System.currentTimeMillis());

        // INFO and loud. A limit that changed underneath a running system is the
        // first thing anyone reading these logs during an incident needs to see,
        // and "why is the bulkhead 200 now" should be answerable from the log
        // rather than from the database's history.
        log.info("psp_config applied for '{}': {}{}", loaded.pspId(), loaded.summary(),
                previous == null ? " (initial)" : " (was " + previous.summary() + ")");
    }

    /**
     * Pushes into the live components.
     *
     * <p>Push rather than have each component pull on every call. A pull would be
     * self-healing and costs a map lookup per request on the hot path; a push
     * costs nothing per request and is re-sent in full whenever anything about a
     * provider changes, so drift cannot outlast one edit. The deciding factor is
     * that a push is <em>observable</em> - there is one log line per change, at
     * the moment of the change, which is what makes "it took effect without a
     * restart" a thing that can be demonstrated rather than inferred.
     */
    private void push(ProviderConfig config) {
        breakers.reconfigure(config.pspId(), config.breakerConfig());

        if (bulkhead instanceof SemaphoreBulkhead semaphore) {
            semaphore.configure(config.pspId(),
                    config.bulkheadMaxConcurrent(), config.bulkheadMaxWaitMs());
        }
        if (egressLimiter instanceof RedisTokenBucketRateLimiter limiter) {
            limiter.configure(config.pspId(), config.egressBurst(), config.egressTps());
        }
        // deadlineSliceMs and retryMaxAttempts are not pushed anywhere: the
        // adapter reads them from this store per call, because they are
        // arguments to a call rather than state inside a component.
    }

    /**
     * A row deleted from {@code psp_config} stops being a provider.
     *
     * <p>The bulkhead's semaphore and the breaker instance are left behind
     * deliberately rather than torn down. Calls may still be in flight against a
     * provider that was removed a millisecond ago, and releasing a permit into a
     * semaphore that no longer exists is a harder failure to diagnose than a few
     * bytes of retained state on a provider nobody will route to again.
     */
    private void forgetProvidersRemovedFrom(List<ProviderConfig> loaded) {
        java.util.Set<String> present = loaded.stream()
                .map(ProviderConfig::pspId)
                .collect(Collectors.toSet());
        current.keySet().removeIf(pspId -> {
            boolean gone = !present.contains(pspId);
            if (gone) {
                log.warn("provider '{}' removed from psp_config; it will no longer be routable", pspId);
            }
            return gone;
        });
    }

    public Optional<ProviderConfig> find(String pspId) {
        return Optional.ofNullable(current.get(pspId));
    }

    /** Every provider this service knows about, ordered so output is stable. */
    public Map<String, ProviderConfig> all() {
        return java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(current));
    }

    public List<ProviderConfig> enabled() {
        return current.values().stream()
                .filter(ProviderConfig::enabled)
                .sorted(java.util.Comparator.comparingInt(ProviderConfig::priority)
                        .thenComparing(ProviderConfig::pspId))
                .toList();
    }

    public long reloads() {
        return reloads.sum();
    }

    public long changesApplied() {
        return changesApplied.sum();
    }

    public long failures() {
        return failures.sum();
    }

    public long secondsSinceLastChange() {
        long at = lastAppliedAtMs.get();
        return at == 0 ? -1 : (System.currentTimeMillis() - at) / 1000;
    }
}
