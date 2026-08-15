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
}
