package com.payorch.infra.observability;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Placeholder for the metrics and tracing wiring.
 *
 * <p>Phase 4 adds Micrometer timers publishing <em>histogram buckets</em>
 * rather than pre-computed percentiles, OpenTelemetry spans on the interesting
 * seams, and trace/log correlation via MDC.
 *
 * <p>The bucket detail matters and is not a formatting preference: percentiles
 * do not aggregate. A P99 computed per instance cannot be averaged across
 * instances to get the fleet P99 - the only correct approach is to export
 * buckets and merge them server-side.
 */
@AutoConfiguration
public class ObservabilityAutoConfiguration {
}
