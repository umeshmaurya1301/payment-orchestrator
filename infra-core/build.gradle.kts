// infra-core is a standalone Gradle build. It is consumed two ways:
//   1. as an included build (the default - see ../settings.gradle.kts)
//   2. as a published snapshot, via `./gradlew publishToMavenLocal`
//
// It deliberately does NOT apply the Spring Boot plugin. These are libraries,
// not applications: no bootJar, no executable archive, no main class.

plugins {
    base
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

allprojects {
    group = "com.payorch.infra"
    version = "0.1.0-SNAPSHOT"
}

// An included build only ever runs the tasks the consuming build asks it for.
// The services depend on the starters' *jars*, so a plain `./gradlew build` at
// the root compiles and packages these modules but never runs a single test
// here. Exposing one aggregate task gives the root build something to depend
// on - see the `build` wiring in ../build.gradle.kts.
tasks.register("buildAll") {
    group = "build"
    description = "Builds and tests every starter in this build."
    dependsOn(subprojects.map { "${it.path}:build" })
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(catalog.findVersion("java").get().requiredVersion.toInt()))
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation", "-Xlint:unchecked"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    dependencies {
        val bom = platform(catalog.findLibrary("spring-boot-bom").get())
        add("api", bom)
        add("annotationProcessor", bom)
        add("testImplementation", bom)
        add("testImplementation", catalog.findLibrary("spring-boot-starter-test").get())
        add("testRuntimeOnly", catalog.findLibrary("junit-platform-launcher").get())

        // Every starter is an autoconfiguration provider.
        add("implementation", catalog.findLibrary("spring-boot-autoconfigure").get())
        add("annotationProcessor", catalog.findLibrary("spring-boot-configuration-processor").get())
    }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
    }
}
