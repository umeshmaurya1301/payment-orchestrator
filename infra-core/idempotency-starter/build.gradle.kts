description = "Idempotency keys, request fingerprinting, in-flight markers, response replay. Filled in during phases 1 and 7."

dependencies {
    // compileOnly, matching the other starters: every service that can supply an
    // IdempotencyStore already has a logging implementation on its classpath,
    // and a library that drags in its own is how two SLF4J bindings end up
    // fighting on startup. Needed at all only since phase 7a, for the one WARN
    // that reports a record written before fingerprinting existed.
    compileOnly(libs.slf4j.api)
    testImplementation(libs.slf4j.api)
}
