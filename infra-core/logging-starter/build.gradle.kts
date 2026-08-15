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
}
