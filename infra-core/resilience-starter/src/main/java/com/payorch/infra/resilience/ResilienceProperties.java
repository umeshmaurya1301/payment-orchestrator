package com.payorch.infra.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the resilience layer, under {@code payorch.resilience}.
 *
 * <p>Grows one nested block per sub-step of phase 3, so what is configurable is
 * a readable summary of what has actually been built and measured so far.
 */
@ConfigurationProperties(prefix = "payorch.resilience")
public record ResilienceProperties(Deadline deadline, Retry retry) {

    public ResilienceProperties {
        if (deadline == null) {
            deadline = new Deadline(null, null, null, null);
        }
        if (retry == null) {
            retry = new Retry(null, null, null, null, null);
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
}
