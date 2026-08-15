description = "The chaos layer that lives inside the JVM: bean-level assaults, plus bespoke seams for the two faults no off-the-shelf tool can reach cleanly."

// ---------------------------------------------------------------------------
// Why chaos-monkey-spring-boot is NOT a dependency here
// ---------------------------------------------------------------------------
// The phase-2 plan called for Spring Boot Chaos Monkey as the in-process layer,
// with the caveat: confirm Boot 4 compatibility on first use, and if it has not
// caught up, replace the layer rather than pinning a service back to Boot 3.
//
// It has not caught up. Version 4.0.0 is the latest release and its POM does
// declare Boot 4.0.2, which is why it looked safe. On Boot 4.1 it is inert:
//
//   * `ChaosMonkeyConfiguration` appears in the conditions report as an
//     unconditional match, so it looks wired;
//   * no startup banner is printed;
//   * with `enabled=true`, `latencyActive=true` and a 2000ms latency range, a
//     request through an assaulted @Service took 76ms.
//
// Its REST endpoint is also built on `@RestControllerEndpoint`, which Boot 4
// still ships but no longer auto-configures a discoverer for, so
// `/actuator/chaosmonkey` 404s and the layer cannot be toggled at runtime
// either.
//
// A dependency that silently does nothing is worse than no dependency: every
// experiment run against it would have produced a clean "no effect" result that
// looked like a finding about the system. So this module implements the layer
// directly - see BeanAssaultAspect. It is perhaps 150 lines, it targets only
// our own beans, and it is togglable through a modern @Endpoint.

dependencies {
    // Spring AOP, for the bean-level assault. `api` because a consuming service
    // needs proxying enabled for the aspect to apply at all.
    //
    // spring-aop + aspectjweaver rather than spring-boot-starter-aop: that
    // starter is gone in Boot 4. Same module split that hid Flyway in phase 0
    // and moved MockMvc's test support in phase 1, and it fails the same way -
    // Gradle reports "Could not find ...:aop:" with an empty version, because
    // the BOM has nothing to say about an artifact that no longer exists.
    api(libs.spring.aop)
    api(libs.aspectjweaver)

    // The seams and the assault both contribute actuator endpoints. compileOnly
    // because a service without actuator simply does not get them - it should
    // not be forced to take a management surface it did not ask for.
    compileOnly(libs.spring.boot.starter.actuator)
    testImplementation(libs.spring.boot.starter.actuator)
}
