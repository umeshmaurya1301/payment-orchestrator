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
        mavenCentral()
    }
    // `gradle/libs.versions.toml` is picked up automatically for this build.
    // The infra-core build points back at the same file explicitly.
}

// ---------------------------------------------------------------------------
// infra-core as an INCLUDED BUILD (a composite build), not publishToMavenLocal.
//
// Why: edits to a starter are picked up by the services on the next build with
// no publish step and no version bump. `./gradlew :services:payments-edge:test`
// will rebuild logging-starter from source first.
//
// The substitutions below are what make that work. A service declares an
// ordinary *module* dependency:
//
//     implementation("com.payorch.infra:logging-starter")
//
// and Gradle swaps it for the local project. Gradle would in fact substitute
// these automatically, because the coordinates match a project in the included
// build - but the mapping is written out explicitly so it is visible, and so a
// coordinate rename fails loudly here instead of silently resolving a stale
// jar from a repository.
//
// To pin a version instead (e.g. to freeze infra-core while working on a
// service), comment out `includeBuild` and publish a snapshot:
//     ./gradlew -p infra-core publishToMavenLocal
// ---------------------------------------------------------------------------
includeBuild("infra-core") {
    dependencySubstitution {
        substitute(module("com.payorch.infra:logging-starter"))
            .using(project(":logging-starter"))
        substitute(module("com.payorch.infra:web-starter"))
            .using(project(":web-starter"))
        substitute(module("com.payorch.infra:resilience-starter"))
            .using(project(":resilience-starter"))
        substitute(module("com.payorch.infra:idempotency-starter"))
            .using(project(":idempotency-starter"))
        substitute(module("com.payorch.infra:observability-starter"))
            .using(project(":observability-starter"))
    }
}

include(
    "services:payments-edge",
    "services:payment-orchestrator",
    "services:psp-router",
    "services:psp-connector",
    "services:ledger-notifier",
    "services:mock-psp-simulator",
)
