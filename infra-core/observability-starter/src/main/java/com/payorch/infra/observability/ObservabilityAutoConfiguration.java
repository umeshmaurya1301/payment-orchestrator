package com.payorch.infra.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires phase 4's observability into any service that takes this starter.
 *
 * <p>Two things live here, and they answer different questions:
 *
 * <ul>
 *   <li>{@link Seams} - manual spans on the four seams auto-instrumentation
 *       cannot name. <em>What happened to this payment.</em></li>
 *   <li>{@link ProviderLatency} - a rolling per-provider percentile over merged
 *       histogram buckets. <em>What is this provider like right now</em>, and
 *       the input phase 5 routes on.</li>
 * </ul>
 *
 * <p>Everything else - the {@code Tracer}, the OTLP exporters, the MDC
 * correlation that puts {@code traceId} and {@code spanId} on every log line -
 * comes from {@code spring-boot-starter-opentelemetry}, which this starter
 * exposes as an {@code api} dependency. That is deliberate: a service asking to
 * be observable should not also have to remember a second dependency, and phase
 * 0 already reserved {@code TRACE_ID} and {@code SPAN_ID} in {@code LogFields}
 * for the day they started being populated.
 */
@AutoConfiguration
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityAutoConfiguration {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ObservabilityAutoConfiguration.class);

    /**
     * Logs the tracing settings actually in effect, once, at startup.
     *
     * <p>The same reason 3a's deadline report exists, and the same hour lost:
     * every property here has a sensible default, so a misplaced or misspelled
     * key is indistinguishable from an absent one. A sampling probability that
     * silently stayed at its default is a phase-4 experiment whose traces are
     * 1% of what the writeup claims.
     */
    @Bean
    public org.springframework.beans.factory.InitializingBean tracingConfigReport(
            ObservabilityProperties properties) {
        return () -> log.info("observability: rolling window {}s, provider latency buckets {}",
                properties.rollingWindowSeconds(), RollingLatency.BUCKET_UPPER_BOUNDS_MS.length);
    }

    @Bean
    @ConditionalOnMissingBean
    public RollingLatency rollingLatency(ObservabilityProperties properties) {
        return new RollingLatency(properties.rollingWindowSeconds());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(MeterRegistry.class)
    public RollingLatencyMetrics rollingLatencyMetrics(RollingLatency rollingLatency) {
        return new RollingLatencyMetrics(rollingLatency);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderLatency providerLatency(RollingLatency rollingLatency,
                                           ObjectProvider<RollingLatencyMetrics> metrics) {
        return new ProviderLatency(rollingLatency, metrics.getIfAvailable());
    }

    /**
     * {@code ObjectProvider}, because a service can be traced without this
     * starter having produced the {@code Tracer} - and because a service with
     * tracing disabled should lose its spans, not fail to start. A missing
     * {@code Tracer} here yields a no-op {@link Seams} rather than a
     * {@code NoSuchBeanDefinitionException} at 3am.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(Tracer.class)
    public Seams seams(ObjectProvider<Tracer> tracer) {
        Tracer resolved = tracer.getIfAvailable();
        if (resolved == null) {
            log.warn("no Tracer bean: seam spans are disabled, only auto-instrumentation will appear");
            return new Seams(io.micrometer.tracing.Tracer.NOOP);
        }
        return new Seams(resolved);
    }
}
