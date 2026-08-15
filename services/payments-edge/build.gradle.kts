plugins {
    id("org.springframework.boot")
}

description = "REST surface, API-key auth, rate limiting, idempotency, and the origin of the deadline budget."

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)

    // Resolved to the local project by the substitutions in settings.gradle.kts.
    implementation(libs.payorch.logging.starter)
    implementation(libs.payorch.web.starter)
}