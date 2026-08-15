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
}