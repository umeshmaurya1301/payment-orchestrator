# Phase 0 — Foundations

| | |
|---|---|
| **Estimate** | ~1 week |
| **Depends on** | nothing |
| **Delivers** | a healthy, empty skeleton |
| **Status** | **complete** — 30 tests, 8 containers healthy |

> This document is retrospective: it records what was actually built and what
> actually went wrong, not what was planned. The failures are the useful part.

## Goal

`docker compose up` produces a healthy, empty skeleton.

## Why here

Two things in this phase are cheap now and miserable later:

- **PII masking.** Retrofitting masking across six services means auditing every
  log call site in the codebase. Built first, it is a dependency every service
  simply has.
- **Virtual threads.** `spring.threads.virtual.enabled=true` changes how pool
  sizing behaves. Turning it on in phase 7 means every measurement taken before
  it is invalidated.

Everything else here is ordinary scaffolding.

## Implementation

### 1. Build system

| Component | Version | Note |
|---|---|---|
| Java | 25.0.3 LTS | toolchain-pinned |
| Gradle | 9.7.0 | wrapper, `networkTimeout=180000` |
| Spring Boot | 4.1.0 | latest GA targeting Java 25 |

- One version catalog at `gradle/libs.versions.toml`. `infra-core/settings.gradle.kts`
  reads **that exact file** via `from(files("../gradle/libs.versions.toml"))`
  rather than keeping a second copy.
- `infra-core` is an **included build** with explicit `dependencySubstitution`
  for all five starters.
- Plain jar disabled for boot projects, so `build/libs` holds exactly one jar.

### 2. `logging-starter`

The only substantial code in this phase.

| Class | Role |
|---|---|
| `Sensitive` / `MaskStrategy` | annotation, 7 strategies |
| `Masking` | primitives + Luhn, shared by both controls so they agree |
| `SensitiveValueSerializer` / `SensitiveSerializerModifier` / `SensitiveJacksonModule` | annotation-driven masking on any mapper |
| `SensitiveMapperBuilderDecorator` | registers the module on the **encoder's own** mapper |
| `Redactor` / `SensitiveDataValueMasker` | Luhn-gated regex net over log output |
| `LogFields` / `LogEvent` | field schema with an **enforced** allowlist |
| `logback-payorch.xml` | wires both controls onto the encoder |

### 3. `web-starter`

`CorrelationIdFilter`, `ProblemDetailHandler`, `ApiException`, autoconfiguration.

### 4. Three empty shells

`resilience-starter`, `idempotency-starter`, `observability-starter` — module,
build file, autoconfiguration class, `AutoConfiguration.imports`. The
registration path is proven before anything depends on it.

### 5. Six services

Each: `/actuator/health`, virtual threads on, both starters, `logback-spring.xml`
deriving `SERVICE_NAME` from `spring.application.name`.
`payment-orchestrator` additionally owns MySQL, Hikari, and Flyway `V1__baseline.sql`.

### 6. Docker

One shared `Dockerfile` selected by build arg. Profiles: `core` (default),
`async` (phase 6), `obs` (phase 4).

## Key decisions

**Included build, not `publishToMavenLocal`.** Edits to a starter propagate on
the next build with no publish step. More importantly, a separate build
*structurally cannot* depend back on a service — which is what stops a shared
library from slowly absorbing business logic.

**`-XX:MaxRAMPercentage=70` with `mem_limit`, not `-Xmx`.** The heap tracks the
container limit instead of being pinned to a number that stops matching.

**`exec` in the Docker entrypoint.** The JVM becomes PID 1 and receives SIGTERM
directly. Without it the shell swallows the signal and phase 7's
graceful-shutdown and Pumba work is silently untestable.

**Redis `noeviction`, not `allkeys-lru`.** From phase 7 Redis holds idempotency
in-flight markers. Under LRU a memory spike silently evicts them and duplicate
suppression degrades into a coin flip exactly when load is highest.

**Allowlist over denylist.** `LogEvent.with()` throws on any name outside
`LogFields`. A denylist fails silently the first time someone adds a field.

**A fifth starter, `web-starter`.** RFC-7807 and the correlation filter needed a
home; putting them in `logging-starter` would drag a servlet dependency into a
module that should stay pure.

## Exit criteria

- [x] Cold `docker compose up` → all core containers healthy (8/8)
- [x] All six services 200 on `/actuator/health`
- [x] `./gradlew build` passes from a clean clone (verified by actually cloning)
- [x] A unit test proves `@Sensitive` fields are masked in serialized log output

## Traps hit

**Boot 4 split autoconfiguration into per-technology modules, and it fails
silently.** `org.flywaydb:flyway-core` on the classpath produced no migrations,
no error, and no log line. `spring-boot-starter-flyway` is required. Caught only
by querying `flyway_schema_history` — the container reported healthy throughout.
Expect this shape for any Boot 3 dependency carried over by artifact name.

**Boot 4.1 ships Jackson 3** (`tools.jackson`, 3.1.4). `JsonSerializer`→`ValueSerializer`,
`SerializerProvider`→`SerializationContext`, `BeanSerializerModifier`→`ValueSerializerModifier`,
`Module`→`JacksonModule`. Every Jackson snippet online is 2.x and will not compile.

**`./gradlew build` ran 6 tests, not 30.** Gradle runs tasks in an included
build only when the consuming build needs their output — and the services need
the starters' *jars*, not their test results. Every starter was compiled and
packaged while none of its tests ran. The local count of 30 was stale XML from a
separate `-p infra-core build`. This made the exit criterion above *vacuously
true*. Fixed with an `infra-core:buildAll` aggregate that root `build` depends on.

**Gradle 9 no longer adds the JUnit Platform launcher implicitly.** Every test
task fails to start until `junit-platform-launcher` is on `testRuntimeOnly`.

**`new LoggerContext()` has no MDC adapter.** A hand-built Logback context blows
up inside the encoder on any event carrying MDC. Tests must take the context
from `LoggerFactory.getILoggerFactory()`.

**Windows: `gradlew` needs LF endings and mode 755, and clones need
`core.longpaths`.** All three break only on other people's machines. The
long-path failure is the nastiest — a failed checkout still leaves a repo, and
`./gradlew build` on the half-empty tree reports BUILD SUCCESSFUL.

## Interview payload

Composite builds and why unidirectional dependency is a structural guarantee
rather than a convention. Defence in depth for PII, and specifically why the
regex layer is the *least* trusted rather than the headline control. The Luhn
gate as a false-positive/false-negative trade-off deliberately tuned toward
over-masking.

**Be ready for:** *"Your regex could mask a random order ID."* Yes — roughly 1
in 10 digit strings of card length pass Luhn. That is the correct trade for a
last-resort net, which is exactly why it is layer 3 and tokenization is layer 1.
