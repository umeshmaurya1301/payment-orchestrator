plugins {
    base
    // On the classpath for every project, applied by none. Service modules opt
    // in with a bare `id("org.springframework.boot")` (no version).
    alias(libs.plugins.spring.boot) apply false
}

// Resolved once here rather than reaching for the `libs` script accessor from
// inside the `subprojects` lambda, where it is not in scope.
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

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
        // Every service here is a servlet application, and MockMvc is how their
        // controllers are tested. Boot 4 moved that support out of
        // spring-boot-starter-test into a separate module - see the catalog.
        add("testImplementation", catalog.findLibrary("spring-boot-webmvc-test").get())
        add("testRuntimeOnly", catalog.findLibrary("junit-platform-launcher").get())
    }
}

// ---------------------------------------------------------------------------
// Phase 4: the PAN-leak test.
//
// Not wired into `check`, and that is a considered decision rather than a
// shortcut. This test needs a LIVE STACK - it drives real payments through
// five containers and then scans what they wrote. Hanging it off `check` would
// make `./gradlew build` fail on a laptop with Docker closed, and a test that
// fails for the wrong reason gets excluded within a week.
//
// It is a build gate where a build gate can exist: CI runs
// `docker compose up -d --build` and then this task, and a leak fails the
// pipeline. Locally it is one command.
//
//     ./gradlew build && docker compose up -d --build
//     ./gradlew panLeakTest
//
// The script self-tests before it scans: it points the scanner at a file
// containing a known PAN, VPA and mobile number and requires it to go red
// first. A leak test whose healthy state is silence is the easiest kind to
// break without noticing, and every way of breaking it - bad classpath,
// unreadable input, a regex that stopped matching - looks exactly like success.
// ---------------------------------------------------------------------------
tasks.register<Exec>("panLeakTest") {
    group = "verification"
    description = "Drives the k6 smoke suite against a live stack and fails if any PAN, VPA or mobile number leaked."

    // The scanner runs as a single-file source program against the logging
    // starter's jar, so it uses the SAME Luhn check and the SAME patterns the
    // runtime masking uses. A second copy could disagree with the thing it
    // audits, and the drift is never symmetric - it is always the scanner that
    // ends up more lenient, because that is the direction that makes a red
    // build go green. The jar itself is a pre-published Maven Local artifact
    // (org.infra:infra-logging) now, not something this build produces, so
    // there is nothing here to depend on.

    // Not just "bash". On Windows that resolves to WSL's bash, which is a
    // different machine with a different filesystem and no Docker socket - the
    // failure is `execvpe(/bin/bash) failed`, which reads like a broken script
    // rather than the wrong interpreter. Git Bash is the shell this repo's
    // scripts are written for and the one the README tells you to use.
    val gitBash = listOf(
        System.getenv("ProgramFiles")?.let { "$it/Git/bin/bash.exe" },
        System.getenv("ProgramW6432")?.let { "$it/Git/bin/bash.exe" },
        System.getenv("LOCALAPPDATA")?.let { "$it/Programs/Git/bin/bash.exe" },
    ).filterNotNull().firstOrNull { File(it).exists() }

    val shell = if (System.getProperty("os.name").startsWith("Windows")) {
        gitBash ?: throw GradleException(
            "Git Bash not found. panLeakTest runs a POSIX script and must not fall back to WSL bash."
        )
    } else {
        "bash"
    }

    commandLine(shell, "tools/panscan/pan-scan.sh", "--load")

    // Exec fails the build on a non-zero exit by default. Stated explicitly
    // because the phase plan is emphatic about it: a leak test that is allowed
    // to warn is decoration.
    isIgnoreExitValue = false
}
