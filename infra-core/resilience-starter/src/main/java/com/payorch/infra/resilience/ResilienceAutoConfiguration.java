package com.payorch.infra.resilience;

import com.payorch.infra.resilience.breaker.CircuitBreakerMetrics;
import com.payorch.infra.resilience.breaker.CircuitBreakers;
import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlineFilter;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import com.payorch.infra.resilience.retry.Backoff;
import com.payorch.infra.resilience.retry.FailureClassifier;
import com.payorch.infra.resilience.retry.RetryBudget;
import com.payorch.infra.resilience.retry.RetryMetrics;
import com.payorch.infra.resilience.retry.Retrier;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * Wires the resilience layer into any service that has a web stack.
 *
 * <p>Phase 3 builds this up one sub-step at a time, and each one lands only
 * after the experiment that justifies it is written. So far: <strong>3a</strong>
 * the deadline budget, and <strong>3b</strong> retry - classification, full
 * jitter and a retry budget. Circuit breaker, bulkhead and the rate limiters
 * follow in order, each with its own before/after graph.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(Filter.class)
@EnableConfigurationProperties(ResilienceProperties.class)
public class ResilienceAutoConfiguration {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ResilienceAutoConfiguration.class);

    /**
     * Logs the deadline settings that are actually in effect, once, at startup.
     *
     * <p>This exists because of a real hour lost. The properties record supplies
     * sensible defaults for every field, which is good ergonomics and means a
     * <em>misplaced</em> configuration block is indistinguishable from an absent
     * one: put the YAML under {@code server:} instead of {@code payorch:} by two
     * spaces and the service starts happily on defaults, and the only symptom is
     * an experiment whose numbers quietly describe the wrong configuration.
     *
     * <p>One INFO line makes that visible in the first second instead of after
     * the run.
     */
    @Bean
    public org.springframework.beans.factory.InitializingBean deadlineConfigReport(
            ResilienceProperties properties) {
        return () -> {
            ResilienceProperties.Deadline d = properties.deadline();
            log.info("deadline budget: {}ms (max {}ms, min slice {}ms, inbound header {})",
                    d.budgetMs(), d.maxBudgetMs(), d.minSliceMs(),
                    d.trustInboundHeader() ? "trusted" : "IGNORED");

            ResilienceProperties.Retry r = properties.retry();
            log.info("retry: up to {} retries, backoff {}-{}ms full jitter, budget {}% of traffic",
                    r.maxRetries(), r.baseDelayMs(), r.maxDelayMs(),
                    Math.round(r.budgetRatio() * 100));

            ResilienceProperties.Breaker b = properties.breaker();
            log.info("circuit breaker: opens at {}% faults over {}s (min {} calls), "
                            + "{}s open then {} half-open probes",
                    b.failureRateThreshold(), b.windowSeconds(), b.minimumCalls(),
                    b.waitInOpenSeconds(), b.halfOpenPermits());
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public FailureClassifier failureClassifier() {
        return new FailureClassifier();
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryBudget retryBudget(ResilienceProperties properties) {
        ResilienceProperties.Retry retry = properties.retry();
        return new RetryBudget(retry.budgetMaxTokens(), retry.budgetRatio());
    }

    @Bean
    @ConditionalOnMissingBean
    public Retrier retrier(ResilienceProperties properties,
                           FailureClassifier classifier,
                           RetryBudget budget) {
        ResilienceProperties.Retry retry = properties.retry();
        return new Retrier(
                retry.maxRetries(),
                properties.deadline().minSliceMs(),
                classifier,
                budget,
                new Backoff(retry.baseDelayMs(), retry.maxDelayMs()));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(io.micrometer.core.instrument.MeterRegistry.class)
    public RetryMetrics retryMetrics(Retrier retrier) {
        return new RetryMetrics(retrier);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(io.micrometer.core.instrument.MeterRegistry.class)
    public CircuitBreakerMetrics circuitBreakerMetrics(CircuitBreakers breakers) {
        return new CircuitBreakerMetrics(breakers);
    }

    /**
     * Breaker state changes are republished as Spring application events, so
     * phase 5's routing can consume them by declaring a listener rather than by
     * reaching into the registry. Nothing listens yet; emitting now makes that a
     * wiring change later instead of a redesign.
     */
    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakers circuitBreakers(
            ResilienceProperties properties,
            org.springframework.context.ApplicationEventPublisher events) {
        ResilienceProperties.Breaker b = properties.breaker();
        return new CircuitBreakers(
                CircuitBreakers.config(
                        b.failureRateThreshold(),
                        java.time.Duration.ofSeconds(b.windowSeconds()),
                        b.minimumCalls(),
                        java.time.Duration.ofSeconds(b.waitInOpenSeconds()),
                        b.halfOpenPermits()),
                events::publishEvent);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeadlineFilter deadlineFilter(ResilienceProperties properties) {
        ResilienceProperties.Deadline deadline = properties.deadline();
        return new DeadlineFilter(
                deadline.budgetMs(), deadline.maxBudgetMs(), deadline.trustInboundHeader());
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public DeadlineExecutor deadlineExecutor(ResilienceProperties properties) {
        return new DeadlineExecutor(
                properties.deadline().minSliceMs(), properties.deadline().budgetMs());
    }

    /**
     * The interceptor that writes the remaining budget onto outbound requests.
     *
     * <p>Contributed as a bean for each client to register on its own
     * {@code RestClient.Builder}, rather than through a
     * {@code RestClientCustomizer}. A customizer only reaches builders that come
     * from the container, and every client in this system constructs its own via
     * {@code RestClient.builder()} - so the customizer would apply to nothing at
     * all, quietly, while looking like it covered everything.
     *
     * <p>The explicit version at least fails visibly: a client that does not
     * take this in its constructor plainly does not have it.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(RestClient.class)
    public DeadlinePropagation deadlinePropagation(ResilienceProperties properties) {
        return new DeadlinePropagation(properties.deadline().budgetMs());
    }
}
