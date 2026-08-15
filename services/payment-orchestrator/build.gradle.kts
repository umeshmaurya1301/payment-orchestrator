plugins {
    id("org.springframework.boot")
}

description = "Payment state machine, MySQL, transactional outbox, saga coordination."

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)

    implementation(libs.payorch.logging.starter)
    implementation(libs.payorch.web.starter)

    // The starter, not org.flywaydb:flyway-core on its own. Boot 4 keeps the
    // Flyway autoconfiguration in a separate spring-boot-flyway module that
    // only the starter pulls in; with bare flyway-core the migrations are
    // silently never run.
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.mysql)
    runtimeOnly(libs.mysql.connector)

    // Lets the context test start without a MySQL container. See the comment
    // in PaymentOrchestratorApplicationTest for why Flyway is off there.
    testRuntimeOnly(libs.h2)
}
