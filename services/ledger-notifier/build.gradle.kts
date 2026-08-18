plugins {
    id("org.springframework.boot")
}

description = "Kafka consumers into Mongo. Double-entry ledger, outbound webhooks, reconciliation."

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)

    // Resolved to the local project by the substitutions in settings.gradle.kts.
    implementation(libs.payorch.logging.starter)
    implementation(libs.payorch.web.starter)

    // Phase 4. Traces and the trace-log correlation, so an event consumed forty
    // seconds after the payment can still be tied back to it.
    implementation(libs.payorch.observability.starter)

    // Phase 6e. Balances are relational and transactional; the journal is
    // append-only and unbounded. See V1__ledger.sql for why they are split.
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.mysql)
    runtimeOnly(libs.mysql.connector)
    implementation(libs.payorch.persistence.starter)

    implementation(libs.spring.boot.starter.data.mongodb)
    implementation(libs.spring.kafka)
    implementation(libs.spring.boot.kafka)

    // Phase 6f. The in-process chaos layer, for the one fault no other layer can
    // express: failing THIS consumer, probabilistically, after the record has
    // been polled and before its offset is committed. Toxiproxy would break the
    // connection to the broker instead, which tests Kafka's client rather than
    // this service's retry ladder.
    implementation(libs.payorch.chaos.core)

    // The Prometheus registry, so /actuator/prometheus actually answers.
    //
    // It was missing until phase 6f, and the way that presented is worth the
    // comment: application.yml has listed `prometheus` in
    // management.endpoints.web.exposure.include since phase 0, so the
    // configuration read as though the endpoint were there. Without a registry
    // on the classpath the endpoint is never contributed at all, and the URL
    // returns 404 - while /actuator/metrics happily lists every meter, because
    // those live in the SimpleMeterRegistry Boot falls back to.
    //
    // So the ledger's counters existed, were correct, and were unscrapeable, and
    // the only symptom was an exposure list naming an endpoint that does not
    // exist. Every other service has had this dependency since phase 2.
    runtimeOnly(libs.micrometer.registry.prometheus)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.h2)
    testImplementation(libs.junit.platform.launcher)
}