plugins {
    id("org.springframework.boot")
}

description = "All resilience. Provider adapters and per-provider configuration."

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)

    // Resolved to the local project by the substitutions in settings.gradle.kts.
    implementation(libs.payorch.logging.starter)
    implementation(libs.payorch.web.starter)

    // Read-only access to the token vault. This service holds the second half
    // of the tokenization boundary: it is the only thing downstream of the edge
    // that can turn a token back into a card, and it does so with credentials
    // granted SELECT and nothing else.
    implementation(libs.payorch.tokenization.starter)

    // 3a: the deadline budget. Every outbound call is bounded by what is left of
    // the request's budget, and a hop with too little left declines rather than
    // starting a call it cannot finish.
    implementation(libs.payorch.resilience.starter)

    // 3e's egress limiter. The same Redis the edge uses, deliberately: a
    // provider's contracted TPS is a global number, and enforcing it from a
    // per-instance counter would multiply the limit by the replica count and
    // breach the contract at exactly the moment we scaled out to handle load.
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

    // The JDBC libraries WITHOUT spring-boot-starter-jdbc, on purpose. This
    // service has no application database - only a vault connection, which
    // tokenization-starter builds itself. The starter would drag
    // DataSourceAutoConfiguration in and fail startup demanding a
    // `spring.datasource.url` that should not exist here.
    implementation(libs.spring.jdbc)
    implementation(libs.hikaricp)
    runtimeOnly(libs.mysql.connector)

    testRuntimeOnly(libs.h2)
}
