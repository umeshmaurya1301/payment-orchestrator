package com.payorch.infra.observability;

/**
 * What a service actually injects: record a provider call, ask for a P99.
 *
 * <p>A facade over {@link RollingLatency} and {@link RollingLatencyMetrics} so
 * that the histogram stays free of Micrometer - it is the piece with the
 * interesting arithmetic and the one that most deserves to be unit-testable
 * without a meter registry - while callers still get their series registered
 * without having to remember to.
 */
public class ProviderLatency {

    private final RollingLatency latency;
    private final RollingLatencyMetrics metrics;

    public ProviderLatency(RollingLatency latency, RollingLatencyMetrics metrics) {
        this.latency = latency;
        this.metrics = metrics;
    }

    /**
     * @param latencyMs wall time of the provider call, including whatever the
     *                  network did to it. Not the provider's self-reported
     *                  timing: phase 5 routes on what a payment actually costs
     *                  us, and a provider that is fast behind a slow link is
     *                  slow.
     */
    public void record(String pspId, String operation, long latencyMs) {
        String key = RollingLatency.key(pspId, operation);
        latency.record(key, latencyMs);
        if (metrics != null) {
            metrics.ensureRegistered(key);
        }
    }

    /** @return -1 when nothing has been recorded recently. See {@link RollingLatency#percentileMs}. */
    public long p99Ms(String pspId, String operation) {
        return latency.p99Ms(RollingLatency.key(pspId, operation));
    }

    public long p50Ms(String pspId, String operation) {
        return latency.p50Ms(RollingLatency.key(pspId, operation));
    }

    public long samples(String pspId, String operation) {
        return latency.count(RollingLatency.key(pspId, operation));
    }
}
