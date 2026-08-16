description = "Structured JSON logging + PII masking. The phase-0 foundation everything else logs through."

dependencies {
    // `api`, not `implementation`: consumers write code against Jackson and the
    // encoder's StructuredArguments, so these are part of our public surface.
    api(libs.logstash.logback.encoder)
    api(libs.jackson.databind)

    // logstash-logback-encoder declares logback-classic as optional, so it does
    // not arrive transitively. Services get it from spring-boot-starter-logging;
    // we only need it to compile and to test.
    compileOnly(libs.logback.classic)
    testImplementation(libs.logback.classic)

    // Phase 4. The Logback -> OpenTelemetry bridge, so log lines reach SigNoz
    // attached to the trace that produced them.
    //
    // `api` and in the LOGGING starter rather than the observability one,
    // because logback-payorch.xml is shared by all six services and names this
    // appender. A service without it would log a Logback error about a missing
    // class on every start - noise that teaches people to ignore startup
    // errors. With the class present but no OpenTelemetry SDK installed (which
    // is what psp-router and ledger-notifier have), the appender simply drops
    // what it is given.
    api(libs.opentelemetry.logback.appender)
}
