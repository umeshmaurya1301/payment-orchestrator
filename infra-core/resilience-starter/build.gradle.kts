description = "Deadline budget, retry, circuit breaker, bulkhead, rate limiters. Built one sub-step at a time in phase 3, each after the experiment that justifies it."

// 3a landed: the deadline budget. Deliberately still no resilience4j - the
// budget needed real per-call cancellation, which no library API here provides
// (see DeadlineExecutor), and a dependency added for the sub-steps that have not
// been measured yet would be a component without a "before".

dependencies {
    // The filter and the outbound interceptor. compileOnly, not api: this is a
    // library, and its autoconfiguration is conditional on the consuming service
    // already having a web stack.
    compileOnly(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.web)

    // Retry metrics. compileOnly: a service without Micrometer simply does not
    // get them, and the binder bean is conditional on the registry being there.
    // Without these the retry layer is unfalsifiable - "capped at 10% of
    // traffic" is a claim about a number nobody can see.
    compileOnly(libs.micrometer.core)
    testImplementation(libs.micrometer.core)

    // 3c. `api`, because a consuming service's own code names CircuitBreaker
    // and its State enum when it reacts to the breaker being open.
    api(libs.resilience4j.circuitbreaker)

    // 3e. compileOnly, and the conditional beans matter here more than usual:
    // psp-connector has an egress limiter but no ingress, payment-orchestrator
    // has neither, and neither should be forced to configure a Redis connection
    // to depend on this starter. A service that wants limiters adds the starter
    // itself and the beans appear; one that does not gets UnlimitedRateLimiter
    // and no Redis client at all.
    compileOnly(libs.spring.boot.starter.data.redis)
    testImplementation(libs.spring.boot.starter.data.redis)

    // Phase 4. Carries the trace context across DeadlineExecutor's virtual-thread
    // handoff - without it every downstream call starts a NEW root trace, and a
    // four-hop payment produces four unrelated single-span traces that read as
    // "tracing is broken" rather than "a ThreadLocal did not cross a handoff".
    //
    // compileOnly: it arrives at runtime with micrometer-tracing in any service
    // that takes observability-starter, and a service without tracing falls back
    // to CallDecorator.NONE and behaves exactly as it did before.
    compileOnly(libs.micrometer.context.propagation)
    testImplementation(libs.micrometer.context.propagation)
}
