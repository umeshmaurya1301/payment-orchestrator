plugins {
    id("org.springframework.boot")
}

description = "The chaos source. Injects latency, errors, hangs and duplicate responses."

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)

    // Resolved to the local project by the substitutions in settings.gradle.kts.
    implementation(libs.payorch.logging.starter)
    implementation(libs.payorch.web.starter)

    // The Prometheus registry, so /actuator/prometheus actually answers. Phase 2
    // reads Hikari and JVM state out of it during a run; phase 4 points SigNoz
    // at the same endpoint.
    runtimeOnly(libs.micrometer.registry.prometheus)
}