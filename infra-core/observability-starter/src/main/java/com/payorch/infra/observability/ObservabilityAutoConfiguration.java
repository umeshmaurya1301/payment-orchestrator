package com.payorch.infra.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
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

    /**
     * Only when the appender is on the classpath and an SDK exists. A service
     * with neither logs to the console exactly as it did before phase 4.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass({io.opentelemetry.api.OpenTelemetry.class,
            io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender.class})
    public LogbackOtelInstaller logbackOtelInstaller(
            ObjectProvider<io.opentelemetry.api.OpenTelemetry> openTelemetry) {
        io.opentelemetry.api.OpenTelemetry sdk = openTelemetry.getIfAvailable();
        return sdk == null ? null : new LogbackOtelInstaller(sdk);
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
     * The same window as the latency ring, deliberately. Phase 5 scores a
     * provider from both numbers at once, and reading a success rate over 60 s
     * against a P99 over 10 s would produce a score describing two different
     * moments - which is the kind of error that shows up as routing that
     * oscillates for no visible reason.
     */
    @Bean
    @ConditionalOnMissingBean
    public RollingOutcomes rollingOutcomes(ObservabilityProperties properties) {
        return new RollingOutcomes(properties.rollingWindowSeconds());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(MeterRegistry.class)
    public ProviderOutcomes providerOutcomes(RollingOutcomes rollingOutcomes,
                                             MeterRegistry registry) {
        return new ProviderOutcomes(rollingOutcomes, registry);
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

    /**
     * Trace context that can be written to a column, for phase 6g.
     *
     * <p>Same {@code ObjectProvider} reasoning as {@link #seams}, and one extra:
     * this one needs a {@code Propagator} as well, and a service can have a
     * {@code Tracer} without one if propagation has been switched off. Both
     * fall back to their NOOP instances, which makes {@code capture()} return
     * null and {@code continuing()} run the work plainly - exactly the untraced
     * behaviour the outbox relay had before this existed.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass({Tracer.class, Propagator.class})
    public TraceCarrier traceCarrier(ObjectProvider<Tracer> tracer,
                                     ObjectProvider<Propagator> propagator) {
        Tracer resolvedTracer = tracer.getIfAvailable();
        Propagator resolvedPropagator = propagator.getIfAvailable();
        if (resolvedTracer == null || resolvedPropagator == null) {
            log.warn("no Tracer or Propagator bean: trace context will not cross the Kafka boundary");
            return new TraceCarrier(Tracer.NOOP, Propagator.NOOP);
        }
        return new TraceCarrier(resolvedTracer, resolvedPropagator);
    }
}
