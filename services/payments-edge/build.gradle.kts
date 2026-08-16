plugins {
    id("org.springframework.boot")
}

description = "REST surface, API-key auth, rate limiting, idempotency, and the origin of the deadline budget."

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)

    // merchant and idempotency_record. The payment tables belong to
    // payment-orchestrator; this service only owns what it is the authority on.
    implementation(libs.spring.boot.starter.data.jpa)

    // Resolved to the local project by the substitutions in settings.gradle.kts.
    implementation(libs.payorch.logging.starter)

    // Phase 4. Brings OpenTelemetry tracing with it as an `api` dependency, so
    // traceId and spanId land in MDC - and therefore on every JSON log line -
    // without this service configuring anything. LogFields reserved both keys in
    // phase 0 for exactly this moment.
    implementation(libs.payorch.observability.starter)

    implementation(libs.payorch.web.starter)
    implementation(libs.payorch.persistence.starter)

    // The single entry point for a raw card number. This is the only service
    // that writes to the vault, and it holds credentials granted INSERT.
    implementation(libs.payorch.tokenization.starter)

    implementation(libs.payorch.idempotency.starter)

    // 3a: the deadline budget. Every outbound call is bounded by what is left of
    // the request's budget, and a hop with too little left declines rather than
    // starting a call it cannot finish.
    implementation(libs.payorch.resilience.starter)

    // 3e: the rate limiters' shared store. The starter is what carries the
    // autoconfiguration that builds the connection factory and the template -
    // resilience-starter only compiles against those types.
    implementation(libs.spring.boot.starter.data.redis)

    // The in-process chaos layer: bean-level latency and exception assaults,
    // plus the bespoke seams. Installed always, injecting nothing until
    // /actuator/chaosbeans or /actuator/chaosseams says otherwise - being
    // togglable without a restart is the point, because a restart resets the
    // pools an experiment is measuring.
    implementation(libs.payorch.chaos.core)

    // The Prometheus registry, so /actuator/prometheus actually answers. Phase 2
    // reads Hikari and JVM state out of it during a run; phase 4 points SigNoz
    // at the same endpoint.
    runtimeOnly(libs.micrometer.registry.prometheus)

    runtimeOnly(libs.mysql.connector)

    testRuntimeOnly(libs.h2)
}
