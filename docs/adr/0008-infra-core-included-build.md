# ADR 0008 — `infra-core` as a Gradle included build

**Status:** accepted (phase 0) · **Evidence:** every phase since

## Context

Seven cross-cutting concerns — logging, persistence, resilience, tokenization,
observability, idempotency, chaos — are used by six services. They need to live
somewhere that is not "one of the services".

## Options considered

**A plain multi-module Gradle project.** One `settings.gradle.kts`, everything
compiled together. Simplest, and it works. Rejected because it makes the starters
*look* like part of the application: a service can reach into another service's
package, module boundaries become advisory, and there is nothing that would break
if `payments-edge` imported an orchestrator class.

**Publish the starters to a repository and depend on versions.** The production
answer for a library shared across teams, and it makes the boundary real. Rejected
for a single-developer project: every change to a starter becomes publish, bump,
resolve — which during phases 3 through 7, when the starters changed constantly,
would have dominated the work.

**Copy the code into each service.** Rejected, but worth naming because it is
what happens by default when the other options feel heavy.

**An included build with dependency substitution.** `infra-core` is a separate
Gradle build with its own `settings.gradle.kts`, consumed as `libs.payorch.*`
coordinates that Gradle substitutes with local projects.

## Decision

Included build, with the starters consumed through the version catalog exactly as
if they were published artefacts.

## Consequences

- **The boundary is real.** A starter cannot see a service, because it is a
  different build. The dependency direction is enforced by the build system
  rather than by discipline.
- The starters are **Spring Boot autoconfigurations**, conditional on what the
  consuming service actually has — which is why `payment-orchestrator` could run
  for eight phases without a Redis client while sharing a resilience starter that
  contains Redis rate limiters.
- **That conditionality has a trap, and it cost an afternoon.**
  `@ConditionalOnClass` on a `@Bean` method is evaluated *after* the declaring
  class is introspected, and introspection resolves every method signature — so a
  parameter of type `StringRedisTemplate` breaks the whole autoconfiguration in a
  service without Redis, condition or no condition. Eleven unrelated
  context-load failures, all pointing at an unrelated bean. The rule learned:
  **a condition guards the class it annotates, never the class that declares it.**
- Editing a starter and a service in one commit is seamless, which is the whole
  benefit — and it also means **nothing forces the versioning discipline** a
  published library would. There is no compatibility story between starter
  versions because there is only ever one version.
- The root build does not traverse the included build for every task. `./gradlew
  compileTestJava` at the root does **not** compile the starters' tests, which is
  discovered by a test that should have failed and did not.
