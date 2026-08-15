rootProject.name = "infra-core"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            // Deliberately points at the PARENT build's catalog rather than
            // keeping a second copy here. Two catalogs drift; one does not.
            // This is the only thing coupling infra-core to its parent
            // directory, and it is worth it.
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

include(
    "logging-starter",
    "web-starter",
    "resilience-starter",
    "idempotency-starter",
    "observability-starter",
)
