rootProject.name = "payment-orchestrator"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // Modules may not declare their own repositories - repositories are a
    // build-wide concern, declared once, here.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The infra-core starters (org.infra:infra-*) are published here from
        // the standalone Infra-Core project via `./gradlew publishToMavenLocal`.
        mavenLocal()
        mavenCentral()
    }
    // `gradle/libs.versions.toml` is picked up automatically for this build.
}

include(
    // Phase 9a. Generated gRPC stubs, shared by every service on an internal hop.
    "proto",
    "services:payments-edge",
    "services:payment-orchestrator",
    "services:psp-router",
    "services:psp-connector",
    "services:ledger-notifier",
    "services:mock-psp-simulator",
)
