# Payment Orchestrator

A locally-runnable, chaos-tested multi-PSP payment orchestration platform.

Built in phases, where each resilience component is added **only after the
failure it prevents has been observed and measured**. Every phase from 3 onward
produces a before/after graph in [`docs/experiments/`](docs/experiments/).

> **Status: phase 0 complete.** Skeleton, build, logging foundation and compose
> stack. No business logic yet - phase 1 is the first payment.

---

## Requirements

| | |
|---|---|
| JDK | 25 (toolchain is pinned; `JAVA_HOME` must be a JDK 25) |
| Docker | Engine 29+ with Compose v2 |
| Gradle | none - use the wrapper |

## Run it

```bash
./gradlew build          # compiles, runs tests
./gradlew bootJar        # produces one runnable jar per service
docker compose up -d --build
```

The Docker images copy a jar built on the host rather than building inside a
builder stage, so `bootJar` must run first. See the comment at the top of
[`docker/Dockerfile`](docker/Dockerfile) for why.

Check everything came up:

```bash
docker compose ps
curl -s localhost:8080/actuator/health   # payments-edge
```

### Compose profiles

| Profile | Contains | From |
|---|---|---|
| *(default)* | mysql, redis, payments-edge, payment-orchestrator, psp-router, psp-connector, mock-psp-simulator | phase 0 |
| `async` | + kafka x3 (KRaft), mongo, ledger-notifier | phase 6 |
| `obs` | SigNoz, attached over an external network - see the note in `docker-compose.yml` | phase 4 |

```bash
docker compose --profile async up -d
```

Profiles exist so ClickHouse is not booting during phase 1. Only the default
profile is covered by the phase-0 exit criteria.

## Services

| Service | Port | Owns |
|---|---|---|
| `payments-edge` | 8080 | REST, API-key auth, rate limiting, idempotency, deadline budget origin |
| `payment-orchestrator` | 8081 | Payment state machine, MySQL, transactional outbox, saga coordination |
| `psp-router` | 8082 | Health-based provider selection |
| `psp-connector` | 8083 | All resilience. Provider adapters, per-provider config |
| `ledger-notifier` | 8084 | Kafka consumers to Mongo. Double-entry ledger, webhooks, reconciliation |
| `mock-psp-simulator` | 8085 | Chaos source. Latency, errors, hangs, duplicate responses |

## Repository layout

```
payment-orchestrator/
├── gradle/libs.versions.toml     one version catalog, shared by both builds
├── infra-core/                   a SEPARATE gradle build, wired via includeBuild
│   ├── logging-starter           structured JSON logging + PII masking
│   ├── web-starter               RFC-7807 errors + correlation-ID filter
│   ├── resilience-starter        empty until phase 3
│   ├── idempotency-starter       empty until phases 1 and 7
│   └── observability-starter     empty until phase 4
├── services/                     the six Spring Boot applications
├── docker/                       one shared Dockerfile, selected by build arg
├── docs/experiments/             one page per chaos experiment, from phase 2
└── tools/loadtest/               k6 scripts, from phase 2
```

### Why `infra-core` is an included build

`settings.gradle.kts` pulls it in with `includeBuild` and substitutes the
starter coordinates for local projects. Editing a starter is picked up by the
services on the next build - no publish step, no version bump. It is still a
standalone build, so `./gradlew -p infra-core publishToMavenLocal` produces real
snapshots when a pinned version is wanted instead.

---

## Data protection

The rules the code is built around, in force from phase 0 rather than added as a
cleanup pass:

- **Raw PAN never leaves the edge.** From phase 1, `payments-edge` tokenizes on
  arrival. Every downstream service, log line, Kafka message and database row
  carries `bin + token + last4` and nothing else.
- **CVV is never stored.** Not encrypted, not hashed, not logged. In transit
  only, then discarded.
- **Allowlist, not denylist.** `LogEvent` accepts only field names declared in
  `LogFields`; an unknown name throws rather than being logged. A denylist fails
  silently the first time someone adds a field.

Masking is layered, and the layers are ordered by how much they are trusted:

| Layer | Mechanism | Role |
|---|---|---|
| 1 | Tokenization at the edge (phase 1) | The actual control. Card data does not exist downstream to be leaked. |
| 2 | `@Sensitive` + Jackson serializer | Masks marked fields wherever they are serialized - HTTP responses, event payloads, structured log arguments. |
| 3 | Luhn-validated regex over log output | Last-resort net for what layers 1 and 2 structurally cannot see: exception text, third-party libraries, hand-concatenated messages. |

Layer 3 is **not** the primary control and is not treated as one. It runs on
every value written to the JSON log output, and gates card-number masking behind
a Luhn checksum so that order IDs and trace IDs survive intact.

Phase 4 adds the enforcement that turns this from a claim into a fact: a build
test that runs the k6 suite against a live stack, captures all container log
output, and fails if any Luhn-valid card number appears.

---

## Phases

| Phase | | Status |
|---|---|---|
| 0 | Foundations - build, logging, compose skeleton | done |
| 1 | Vertical slice: one payment, happy path, tokenization | next |
| 2 | Chaos harness and load - the baseline failure report | |
| 3 | Resilience layer, one component at a time | |
| 4 | Observability - SigNoz, trace/log correlation, PAN-leak test | |
| 5 | Health-driven routing - the differentiator | |
| 6 | Async spine - Kafka, outbox, CDC, saga | |
| 7 | Concurrency and idempotency hardening | |
| 8 | Data layer depth - indexing, reconciliation | |
| 9 | gRPC migration, Vault, encryption, audit, erasure | |
| 10 | Packaging: ADRs, experiment writeups, demo script | |

The rule that holds across all of them: **never add a resilience component
without first observing the failure it prevents.** Break it, measure it, then
fix it.
