plugins {
    id("org.springframework.boot")
}

description = "All resilience. Provider adapters and per-provider configuration."

// Phase 7f. --enable-preview, for StructuredTaskScope.
//
// WHAT THIS COSTS, BECAUSE IT IS NOT FREE
//
// StructuredTaskScope is still a preview API in JDK 25 (JEP 505, its fifth
// preview), and preview means two things that matter in production:
//
//   1. THE API CAN CHANGE. It already has, twice - the ShutdownOnSuccess and
//      ShutdownOnFailure subclasses that most tutorials show were replaced by
//      StructuredTaskScope.open(Joiner...). Code written against the old shape
//      does not compile against this JDK at all.
//
//   2. A CLASS FILE COMPILED WITH PREVIEW IS PINNED TO ONE JDK VERSION. javac
//      marks it with minor version 65535, and the JVM refuses to load it unless
//      --enable-preview is passed AND the major version matches exactly. So
//      bumping the base image from 25 to 26 does not produce a deprecation
//      warning or a compile error - it produces a service that will not start,
//      at deploy time, in a container. docker/Dockerfile pins
//      eclipse-temurin:25-jre-alpine and this toolchain pins 25; they have to
//      move together.
//
// Scoped to THIS module rather than applied in the root build, so the blast
// radius is one service and the other five keep producing ordinary class files.
// The phase guide's own instruction is "the API is still evolving across JDK
// versions; pin what you use" - this is the pin, written where somebody
// changing the JDK will trip over it.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("--enable-preview")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-preview")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-preview")
}

dependencies {
    // Phase 9a. The generated stubs, plus a server runtime. netty-shaded so the
    // gRPC transport cannot collide with any other Netty on the classpath - the
    // shaded artifact exists precisely because that collision is common and
    // presents as an unrelated NoSuchMethodError.
    implementation(project(":proto"))
    implementation(libs.grpc.netty.shaded)

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)

    // Resolved to the local project by the substitutions in settings.gradle.kts.
    implementation(libs.payorch.logging.starter)

    // Phase 4. Brings OpenTelemetry tracing with it as an `api` dependency, so
    // traceId and spanId land in MDC - and therefore on every JSON log line -
    // without this service configuring anything. LogFields reserved both keys in
    // phase 0 for exactly this moment.
    implementation(libs.payorch.observability.starter)

    implementation(libs.payorch.web.starter)

    // Read-only access to the token vault. This service holds the second half
    // of the tokenization boundary: it is the only thing downstream of the edge
    // that can turn a token back into a card, and it does so with credentials
    // granted SELECT and nothing else.
    implementation(libs.payorch.tokenization.starter)

    // 3a: the deadline budget. Every outbound call is bounded by what is left of
    // the request's budget, and a hop with too little left declines rather than
    // starting a call it cannot finish.
    implementation(libs.payorch.resilience.starter)

    // 3e's egress limiter. The same Redis the edge uses, deliberately: a
    // provider's contracted TPS is a global number, and enforcing it from a
    // per-instance counter would multiply the limit by the replica count and
    // breach the contract at exactly the moment we scaled out to handle load.
    implementation(libs.spring.boot.starter.data.redis)

    // The in-process chaos layer: bean-level latency and exception assaults,
    // plus the bespoke seams. Installed always, injecting nothing until
    // /actuator/chaosbeans or /actuator/chaosseams says otherwise - being
    // togglable without a restart is the point, because a restart resets the
    // pools an experiment is measuring.
    implementation(libs.payorch.chaos.core)

    // The Prometheus registry, so /actuator/prometheus actually answers. Phase 2
    // reads Hikari and JVM state out of it during a run; phase 4 points SigNoz
    // at the same endpoint.
    runtimeOnly(libs.micrometer.registry.prometheus)

    // The JDBC libraries WITHOUT spring-boot-starter-jdbc, on purpose. This
    // service has no application database - only a vault connection, which
    // tokenization-starter builds itself. The starter would drag
    // DataSourceAutoConfiguration in and fail startup demanding a
    // `spring.datasource.url` that should not exist here.
    implementation(libs.spring.jdbc)
    implementation(libs.hikaricp)
    runtimeOnly(libs.mysql.connector)

    testImplementation(libs.grpc.inprocess)
    testRuntimeOnly(libs.h2)
}
