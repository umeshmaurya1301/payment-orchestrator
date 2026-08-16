package com.payorch.infra.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the resilience layer, under {@code payorch.resilience}.
 *
 * <p>Grows one nested block per sub-step of phase 3, so what is configurable is
 * a readable summary of what has actually been built and measured so far.
 */
@ConfigurationProperties(prefix = "payorch.resilience")
public record ResilienceProperties(Deadline deadline, Retry retry, Breaker breaker, Bulkhead bulkhead,
                                   RateLimit rateLimit) {

    public ResilienceProperties {
        if (deadline == null) {
            deadline = new Deadline(null, null, null, null);
        }
        if (retry == null) {
            retry = new Retry(null, null, null, null, null);
        }
        if (breaker == null) {
            breaker = new Breaker(null, null, null, null, null);
        }
        if (bulkhead == null) {
            bulkhead = new Bulkhead(null, null, null, null);
        }
        if (rateLimit == null) {
            rateLimit = new RateLimit(null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * @param budgetMs           what a request gets when it arrives without a
     *                           budget. 30 s at the edge, matching the phase-3
     *                           plan.
     * @param maxBudgetMs        ceiling applied even to a trusted inbound
     *                           header, so one misconfigured caller cannot pin
     *                           resources indefinitely
     * @param minSliceMs         floor below which a downstream call is declined
     *                           rather than started
     * @param trustInboundHeader whether to believe {@code X-Deadline-Ms} from
     *                           the caller. <strong>False at
     *                           {@code payments-edge}</strong>, whose callers are
     *                           merchants; true between internal services, where
     *                           it is the whole mechanism.
     */
    public record Deadline(Long budgetMs, Long maxBudgetMs, Long minSliceMs, Boolean trustInboundHeader) {

        // Boxed and defaulted here rather than declared as primitives.
        // Constructor binding gives an absent primitive its zero value, and a
        // zero budget means every request is already out of time - a
        // configuration typo would turn into a total outage that reads like a
        // deadline bug.
        public Deadline {
            if (budgetMs == null || budgetMs <= 0) {
                budgetMs = 30_000L;
            }
            if (maxBudgetMs == null || maxBudgetMs <= 0) {
                maxBudgetMs = 60_000L;
            }
            if (minSliceMs == null || minSliceMs < 0) {
                minSliceMs = 50L;
            }
            if (trustInboundHeader == null) {
                // Defaults to NOT trusting. The safe default is the one that
                // cannot be exploited by a caller; services that need the
                // opposite say so explicitly.
                trustInboundHeader = Boolean.FALSE;
            }
        }
    }

    /**
     * @param maxRetries       retries <em>after</em> the first attempt. 2 means
     *                         up to three calls in total.
     * @param baseDelayMs      first backoff ceiling; doubles each retry
     * @param maxDelayMs       cap on the exponential ceiling
     * @param budgetRatio      retries as a fraction of total requests. 0.1 caps
     *                         them at 10% of traffic however bad things get -
     *                         which is what stops a partial outage becoming a
     *                         self-inflicted total one.
     * @param budgetMaxTokens  ceiling on banked credit, so a quiet period cannot
     *                         fund a retry storm the moment things break
     */
    public record Retry(Integer maxRetries, Long baseDelayMs, Long maxDelayMs,
                        Double budgetRatio, Double budgetMaxTokens) {

        public Retry {
            if (maxRetries == null || maxRetries < 0) {
                maxRetries = 2;
            }
            if (baseDelayMs == null || baseDelayMs <= 0) {
                baseDelayMs = 50L;
            }
            if (maxDelayMs == null || maxDelayMs <= 0) {
                maxDelayMs = 1_000L;
            }
            if (budgetRatio == null || budgetRatio < 0) {
                budgetRatio = 0.1;
            }
            if (budgetMaxTokens == null || budgetMaxTokens <= 0) {
                budgetMaxTokens = 100.0;
            }
        }
    }

    /**
     * @param failureRateThreshold  percentage of provider faults in the window
     *                              that opens the breaker. 50 rather than
     *                              something tighter because a healthy provider
     *                              here runs at essentially 0% - the phase-2
     *                              control saw 100% success and the 30-minute
     *                              soak saw one failure in 36,001. Half the
     *                              calls failing is unambiguous, and 3b's retry
     *                              already absorbs transient blips, so the
     *                              breaker should only fire on sustained
     *                              failure.
     * @param windowSeconds         time-based window. See CircuitBreakers for
     *                              why time-based rather than count-based.
     * @param minimumCalls          below this many calls in the window the rate
     *                              is not meaningful and the breaker stays
     *                              closed. Covers the low-traffic case a time
     *                              window is weak at.
     * @param waitInOpenSeconds     how long to stay open before probing
     * @param halfOpenPermits       probe calls allowed in half-open. More than
     *                              one, so a single unlucky probe cannot
     *                              re-open a recovered provider.
     */
    public record Breaker(Integer failureRateThreshold, Integer windowSeconds, Integer minimumCalls,
                          Integer waitInOpenSeconds, Integer halfOpenPermits) {

        public Breaker {
            if (failureRateThreshold == null || failureRateThreshold <= 0 || failureRateThreshold > 100) {
                failureRateThreshold = 50;
            }
            if (windowSeconds == null || windowSeconds <= 0) {
                windowSeconds = 30;
            }
            if (minimumCalls == null || minimumCalls <= 0) {
                minimumCalls = 20;
            }
            if (waitInOpenSeconds == null || waitInOpenSeconds <= 0) {
                waitInOpenSeconds = 10;
            }
            if (halfOpenPermits == null || halfOpenPermits <= 0) {
                halfOpenPermits = 5;
            }
        }
    }

    /**
     * @param kind               {@code semaphore} or {@code threadpool}. The
     *                           default is semaphore: under virtual threads a
     *                           thread-pool bulkhead pays platform-thread cost
     *                           to isolate something already cheap. 3d measures
     *                           both rather than asserting it.
     * @param maxConcurrentCalls in-flight calls allowed per provider. Sized from
     *                           the provider's contracted TPS by Little's law:
     *                           concurrency = TPS x latency. 500 TPS at 40 ms is
     *                           20.
     * @param maxWaitMs          ceiling on waiting for a permit. The actual wait
     *                           is the smaller of this and the request's
     *                           remaining budget, so the two cannot disagree.
     * @param queueCapacity      thread-pool only. Bounded on purpose: an
     *                           unbounded queue turns a bulkhead into an OOM,
     *                           which is exactly how the phase-2 baseline died.
     */
    public record Bulkhead(String kind, Integer maxConcurrentCalls, Long maxWaitMs, Integer queueCapacity) {

        public Bulkhead {
            if (kind == null || kind.isBlank()) {
                kind = "semaphore";
            }
            if (maxConcurrentCalls == null || maxConcurrentCalls <= 0) {
                maxConcurrentCalls = 20;
            }
            if (maxWaitMs == null || maxWaitMs < 0) {
                maxWaitMs = 250L;
            }
            if (queueCapacity == null || queueCapacity <= 0) {
                queueCapacity = 50;
            }
        }
    }

    /**
     * Phase 3e's three layers. Separate capacities and rates rather than one
     * shared pair, because the three are answering different questions and
     * sizing them together would mean sizing at least two of them wrongly.
     *
     * @param enabled          master switch, so an experiment has a clean
     *                         "before" arm without removing the beans
     * @param kind             {@code atomic-lua} or {@code read-modify-write}.
     *                         The second is deliberately broken and exists only
     *                         so 3e can measure over-admission rather than
     *                         assert it.
     * @param merchantBurst    tokens a merchant may spend at once
     * @param merchantPerSec   sustained per-merchant rate. Fairness: one
     *                         merchant's runaway loop must not spend the
     *                         capacity another merchant paid for.
     * @param writeBurst       burst for {@code POST /v1/payments}
     * @param writePerSec      service-wide ceiling for writes. This is the one
     *                         sized from what the service can actually survive -
     *                         3d's 500 rps arm put {@code payments-edge} into
     *                         {@code OutOfMemoryError} twice, and per-merchant
     *                         limits do not bound the sum over merchants.
     * @param readPerSec       service-wide ceiling for status reads, which are
     *                         cheap and should not be priced like an
     *                         authorisation
     * @param egressPerSec     <em>their</em> contracted TPS, per provider. The
     *                         layer that protects the downstream from us.
     * @param bucketTtlSeconds how long an untouched bucket survives in Redis
     */
    public record RateLimit(Boolean enabled, String kind,
                            Integer merchantBurst, Double merchantPerSec,
                            Integer writeBurst, Double writePerSec, Double readPerSec,
                            Double egressPerSec, Integer bucketTtlSeconds) {

        public RateLimit {
            if (enabled == null) {
                enabled = Boolean.TRUE;
            }
            if (kind == null || kind.isBlank()) {
                kind = "atomic-lua";
            }
            if (merchantBurst == null || merchantBurst <= 0) {
                merchantBurst = 100;
            }
            if (merchantPerSec == null || merchantPerSec <= 0) {
                merchantPerSec = 50.0;
            }
            if (writeBurst == null || writeBurst <= 0) {
                writeBurst = 300;
            }
            if (writePerSec == null || writePerSec <= 0) {
                writePerSec = 150.0;
            }
            if (readPerSec == null || readPerSec <= 0) {
                readPerSec = 500.0;
            }
            if (egressPerSec == null || egressPerSec <= 0) {
                // mockpsp's stand-in contract. 3f replaces this with the real
                // per-provider number from psp_config.
                egressPerSec = 200.0;
            }
            if (bucketTtlSeconds == null || bucketTtlSeconds <= 0) {
                bucketTtlSeconds = 3_600;
            }
        }
    }
}
