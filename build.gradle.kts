plugins {
    base
    // On the classpath for every project, applied by none. Service modules opt
    // in with a bare `id("org.springframework.boot")` (no version).
    alias(libs.plugins.spring.boot) apply false
}

// Resolved once here rather than reaching for the `libs` script accessor from
// inside the `subprojects` lambda, where it is not in scope.
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Without this, `./gradlew build` silently skips every test in infra-core.
// Gradle only runs tasks in an included build when the consuming build needs
// their output, and what the services need is the starters' jars - not their
// test results. That made the phase-0 exit criterion ("a unit test proves
// @Sensitive fields are masked") pass locally off stale results while a clean
// clone ran six tests instead of thirty.
tasks.named("build") {
    dependsOn(gradle.includedBuild("infra-core").task(":buildAll"))
}

allprojects {
    group = "com.payorch"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(catalog.findVersion("java").get().requiredVersion.toInt()))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // -parameters keeps constructor/method parameter names in the class
        // file. Spring uses them for binding; without it @ConfigurationProperties
        // constructor binding and some Jackson paths fail at runtime, not compile
        // time, which is a miserable way to find out.
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation", "-Xlint:unchecked"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }

    // The Spring Boot plugin emits two archives: the runnable bootJar and a
    // plain `-plain.jar` that is not runnable. Leaving both in build/libs means
    // the Dockerfile's COPY glob matches two files and fails with a message
    // that does not mention either of them. Nothing consumes these services as
    // a library, so the plain jar has no reason to exist.
    plugins.withId("org.springframework.boot") {
        tasks.named<Jar>("jar") { enabled = false }
    }

    dependencies {
        val bom = platform(catalog.findLibrary("spring-boot-bom").get())
        add("implementation", bom)
        add("annotationProcessor", bom)
        add("testImplementation", bom)
        add("testImplementation", catalog.findLibrary("spring-boot-starter-test").get())
        add("testRuntimeOnly", catalog.findLibrary("junit-platform-launcher").get())
    }
}
