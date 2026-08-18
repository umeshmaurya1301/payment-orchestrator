description = "Micrometer timers, percentile histograms, OpenTelemetry spans, and the rolling per-provider P99 that phase 5 routes on."

dependencies {
    // `api`, deliberately. A service that depends on this starter is asking to
    // be observable, and the autoconfiguration that builds the Tracer and the
    // OTLP exporter has to be on ITS runtime classpath, not hidden behind an
    // implementation edge. This is the one dependency here that every consumer
    // needs whether or not it writes a line of tracing code.
    api(libs.spring.boot.starter.opentelemetry)

    // The Tracer and Observation APIs, for the spans this starter contributes
    // on the seams. compileOnly: they arrive transitively through the starter
    // above at runtime, and declaring them compileOnly keeps this module's own
    // compilation honest about what it uses.
    compileOnly(libs.micrometer.tracing)
    compileOnly(libs.micrometer.observation)
    compileOnly(libs.micrometer.core)
    compileOnly(libs.spring.boot.starter.web)
    compileOnly(libs.opentelemetry.logback.appender)

    testImplementation(libs.micrometer.tracing)
    testImplementation(libs.micrometer.tracing.bridge.otel)
    testImplementation(libs.micrometer.observation)
    testImplementation(libs.micrometer.core)
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.opentelemetry.logback.appender)
}
