description = "Shared HTTP concerns: RFC-7807 error model and the correlation-ID filter."

dependencies {
    // The correlation filter writes to MDC using the shared field schema, so
    // the two starters are genuinely coupled - a service cannot use one
    // meaningfully without the other.
    api(project(":logging-starter"))

    // compileOnly, not api: this is a library. Services bring their own web
    // stack, and the autoconfiguration below is conditional on it being there.
    compileOnly(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.web)
}
