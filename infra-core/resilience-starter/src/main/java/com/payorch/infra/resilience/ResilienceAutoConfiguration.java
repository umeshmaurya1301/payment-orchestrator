package com.payorch.infra.resilience;

import com.payorch.infra.resilience.breaker.CircuitBreakerMetrics;
import com.payorch.infra.resilience.bulkhead.Bulkhead;
import com.payorch.infra.resilience.bulkhead.BulkheadMetrics;
import com.payorch.infra.resilience.bulkhead.SemaphoreBulkhead;
import com.payorch.infra.resilience.bulkhead.ThreadPoolBulkhead;
import com.payorch.infra.resilience.breaker.CircuitBreakers;
import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlineFilter;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import com.payorch.infra.resilience.ratelimit.EndpointCosts;
import com.payorch.infra.resilience.ratelimit.EndpointRateLimiter;
import com.payorch.infra.resilience.ratelimit.RateLimitMetrics;
import com.payorch.infra.resilience.ratelimit.RateLimiter;
import com.payorch.infra.resilience.ratelimit.RateLimiters;
import com.payorch.infra.resilience.ratelimit.ReadModifyWriteRateLimiter;
import com.payorch.infra.resilience.ratelimit.RedisTokenBucketRateLimiter;
import com.payorch.infra.resilience.ratelimit.UnlimitedRateLimiter;
import com.payorch.infra.resilience.retry.Backoff;
import com.payorch.infra.resilience.retry.FailureClassifier;
import com.payorch.infra.resilience.retry.RetryBudget;
import com.payorch.infra.resilience.retry.RetryMetrics;
import com.payorch.infra.resilience.retry.Retrier;
import java.util.Map;

import jakarta.servlet.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
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

            ResilienceProperties.Bulkhead bh = properties.bulkhead();
            log.info("bulkhead: {}, {} concurrent calls per provider, wait up to {}ms{}",
                    bh.kind(), bh.maxConcurrentCalls(), bh.maxWaitMs(),
                    "threadpool".equals(bh.kind()) ? ", queue " + bh.queueCapacity() : "");

            ResilienceProperties.RateLimit rl = properties.rateLimit();
            if (!rl.enabled()) {
                log.info("rate limiters: DISABLED - every layer admits unconditionally");
            } else {
                log.info("rate limiters ({}): merchant {}/s burst {}, write {}/s burst {}, "
                                + "read {}/s, egress {}/s",
                        rl.kind(), rl.merchantPerSec(), rl.merchantBurst(),
                        rl.writePerSec(), rl.writeBurst(), rl.readPerSec(), rl.egressPerSec());
                if ("read-modify-write".equalsIgnoreCase(rl.kind())) {
                    // WARN, because this implementation is only ever correct as
                    // an experiment arm and a service left running on it would
                    // over-admit silently. The whole failure mode is that
                    // nothing looks wrong.
                    log.warn("rate limiter is the NON-ATOMIC read-modify-write implementation "
                            + "- it over-admits under concurrency and is for experiments only");
                }
            }
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

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(io.micrometer.core.instrument.MeterRegistry.class)
    public BulkheadMetrics bulkheadMetrics(Bulkhead bulkhead) {
        return new BulkheadMetrics(bulkhead);
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

    /**
     * Semaphore by default, and 3d's measurements say it should stay that way -
     * same admission outcome as the thread pool, a 3.7x better tail, 107 MiB
     * less upstream heap and 20 fewer platform threads. {@code threadpool}
     * remains selectable because the argument for a default is worth more when
     * the alternative can still be run.
     *
     * <p>Destroy-method inference is left on deliberately: it resolves to
     * {@link ThreadPoolBulkhead#shutdown()} for the pooled implementation, and
     * to nothing at all for the semaphore, which has no threads to release.
     */
    @Bean
    @ConditionalOnMissingBean
    public Bulkhead bulkhead(ResilienceProperties properties) {
        ResilienceProperties.Bulkhead b = properties.bulkhead();
        long minSlice = properties.deadline().minSliceMs();
        if ("threadpool".equalsIgnoreCase(b.kind())) {
            return new ThreadPoolBulkhead(
                    b.maxConcurrentCalls(), b.queueCapacity(), b.maxWaitMs(), minSlice);
        }
        return new SemaphoreBulkhead(b.maxConcurrentCalls(), b.maxWaitMs(), minSlice);
    }

    /**
     * 3e's limiters, isolated in a nested configuration.
     *
     * <p>Nested rather than declared alongside everything else, and the reason is
     * a Boot trap worth naming. {@code @ConditionalOnClass} on a {@code @Bean}
     * method is evaluated <em>after</em> the declaring class is introspected, and
     * introspection resolves every method signature - so a parameter of type
     * {@code StringRedisTemplate} makes the whole autoconfiguration fail to load
     * in any service without Redis on its classpath, condition or no condition.
     * {@code payment-orchestrator} is exactly that service, and the symptom was
     * eleven unrelated context-load failures pointing at
     * {@code failureClassifier}.
     *
     * <p>A nested class annotated at the type level is skipped whole, so its
     * signatures are never resolved. The general rule: a condition guards the
     * class it annotates, never the class that declares it.
     */
    @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(StringRedisTemplate.class)
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    public static class RateLimiterConfiguration {

        /**
         * The three limiter layers, keyed by the name their metrics carry.
         *
         * <p>Built by one factory rather than as three independent beans so that the
         * {@code kind} switch applies to all of them at once. An experiment that
         * swapped the implementation of only the layer it remembered would compare
         * two systems that differ in more than one place, which is how 3d's first
         * thread-pool run wasted an afternoon.
         */
        @Bean
        @ConditionalOnMissingBean
        public RateLimiters rateLimiters(ResilienceProperties properties,
                                         ObjectProvider<StringRedisTemplate> redis) {
            ResilienceProperties.RateLimit r = properties.rateLimit();
            StringRedisTemplate template = redis.getIfAvailable();

            if (!r.enabled() || template == null) {
                // Both paths land here on purpose. "Turned off for the control arm"
                // and "this service has no Redis" behave identically, and both
                // behave like a limiter that admits everything rather than like a
                // missing bean that fails startup.
                return new RateLimiters(
                        new UnlimitedRateLimiter(), new UnlimitedRateLimiter(), new UnlimitedRateLimiter());
            }

            return new RateLimiters(
                    limiter(template, r, "rl:merchant", r.merchantBurst(), r.merchantPerSec()),
                    new EndpointRateLimiter(
                            Map.of(
                                    EndpointCosts.PAYMENTS_WRITE,
                                    limiter(template, r, "rl:ep:write", r.writeBurst(), r.writePerSec()),
                                    EndpointCosts.PAYMENTS_READ,
                                    limiter(template, r, "rl:ep:read",
                                            (int) Math.ceil(r.readPerSec() * 2), r.readPerSec())),
                            new UnlimitedRateLimiter()),
                    limiter(template, r, "rl:egress",
                            (int) Math.ceil(r.egressPerSec()), r.egressPerSec()));
        }

        private static RateLimiter limiter(StringRedisTemplate template, ResilienceProperties.RateLimit r,
                                           String prefix, int burst, double perSec) {
            if ("read-modify-write".equalsIgnoreCase(r.kind())) {
                return new ReadModifyWriteRateLimiter(template, prefix, burst, perSec, r.bucketTtlSeconds());
            }
            return new RedisTokenBucketRateLimiter(template, prefix, burst, perSec, r.bucketTtlSeconds());
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnClass(io.micrometer.core.instrument.MeterRegistry.class)
        public RateLimitMetrics rateLimitMetrics(RateLimiters rateLimiters) {
            return new RateLimitMetrics(rateLimiters.asMap());
        }
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
