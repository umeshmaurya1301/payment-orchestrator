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

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.h2)
    testImplementation(libs.junit.platform.launcher)
}