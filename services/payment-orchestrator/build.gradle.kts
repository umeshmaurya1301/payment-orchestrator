plugins {
    id("org.springframework.boot")
}

description = "Payment state machine, MySQL, transactional outbox, saga coordination."

dependencies {
    // Phase 9a. The generated stubs and a client transport.
    implementation(project(":proto"))
    implementation(libs.grpc.netty.shaded)

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)

    implementation(libs.payorch.logging.starter)

    // Phase 4. Brings OpenTelemetry tracing with it as an `api` dependency, so
    // traceId and spanId land in MDC - and therefore on every JSON log line -
    // without this service configuring anything. LogFields reserved both keys in
    // phase 0 for exactly this moment.
    implementation(libs.payorch.observability.starter)

    implementation(libs.payorch.web.starter)

    // Phase 6. The async spine: payment events onto Kafka, durably.
    implementation(libs.spring.kafka)

    // UUIDv7 primary keys and the BINARY(16) JPA converter. This service owns
    // every table that carries one.
    implementation(libs.payorch.persistence.starter)

    // 3a: the deadline budget. Every outbound call is bounded by what is left of
    // the request's budget, and a hop with too little left declines rather than
    // starting a call it cannot finish.
    implementation(libs.payorch.resilience.starter)

    // Phase 8a. Redis, and this service has managed without it for eight
    // phases - the rate limiters live at the edge and the egress limiter lives
    // in the connector, so the orchestrator never needed a Redis client.
    //
    // It needs one now for exactly one thing: RedisLock, so that four instances
    // of the UNKNOWN poller do not all fan out to three providers about the same
    // backlog every thirty seconds. That is a cost control rather than a
    // correctness control - the job is idempotent either way, and RedisLock's
    // javadoc is explicit that it cannot be more than that.
    //
    // Worth noting what this changes: ResilienceAutoConfiguration's nested
    // RateLimiterConfiguration is now ACTIVE in this service, because its
    // @ConditionalOnClass(StringRedisTemplate) is satisfied. The limiters it
    // contributes stay unlimited unless payorch.resilience.rate-limit.enabled
    // says otherwise, which it does not here.
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

    // The starter, not org.flywaydb:flyway-core on its own. Boot 4 keeps the
    // Flyway autoconfiguration in a separate spring-boot-flyway module that
    // only the starter pulls in; with bare flyway-core the migrations are
    // silently never run.
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.mysql)
    runtimeOnly(libs.mysql.connector)

    // Lets the context test start without a MySQL container. See the comment
    // in PaymentOrchestratorApplicationTest for why Flyway is off there.
    testImplementation(libs.grpc.inprocess)
    testRuntimeOnly(libs.h2)
}
