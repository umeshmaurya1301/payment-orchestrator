# Reading this repo for the first time

Where to start, in what order, and why - for a beginner opening this codebase
cold, including the `infra-core` starters everything else is built on.

The short version: **don't browse. Follow the order below, then follow
[`docs/phases/`](phases/) the same way** - this repo was built in the order
it should be read, and both are already sequenced for you.

## 0. Orient before you open a single file

~30 minutes, no code yet.

1. Read [`README.md`](../README.md) top to bottom. The mermaid architecture
   diagram and the phase-5 traffic-shift graph are the two pictures the rest
   of the repo explains - keep coming back to them.
2. Actually run it: `docker compose up -d --build`, then make one payment
   (the `curl` example under "Make a payment" in the README). Seeing a real
   payment succeed makes every file below concrete instead of abstract.

## 1. The four starters the vertical slice needs

The eight starters this system is built on - `logging-starter`, `web-starter`,
`persistence-starter`, `tokenization-starter`, `chaos-core`,
`resilience-starter`, `observability-starter`, `idempotency-starter` - now
live in the standalone **Infra-Core** repository (group `org.infra`), not in
this one; this repo consumes them as ordinary published dependencies (see
`gradle/libs.versions.toml`'s `payorch-*` aliases, and the README's "The
`infra-core` starters live in a separate repository" section). Clone that repo
alongside this one to read the source below.

Don't read all eight. Read only the four phase 0/1 actually use, in this
order - `infra-logging` is the one dependency the other three share:

1. **`infra-logging`** - `LogFields.java`, `LogEvent.java`, `Masking.java`.
   Pure, small, no Spring machinery, and every other starter and every
   service logs through it.
2. **`infra-persistence`** - `Uuid7.java`. A one-file module. Explains why
   every ID in the system looks the way it does.
3. **`infra-web`** - `CorrelationIdFilter` and the RFC-7807 error model.
   Small, and it's why every error response across all six services carries
   the same JSON shape.
4. **`infra-tokenization`** - `Pan.java` -> `TokenVault.java` ->
   `PanCipher` / `EnvelopeCipher`. Slow down here. This is the actual
   security boundary the README's PCI section is about.

Leave `infra-chaos`, `infra-resilience`, `infra-observability`, and
`infra-idempotency` for later - they belong to phases 2-7 and will make more
sense once you've seen the plain, undefended version of the system they were
added to protect.

## 2. One real service: `payments-edge`

It's the entry point and the smallest service, and it directly exercises all
four starters above. Read in call order, not file-tree order:

1. `PaymentsEdgeApplication.java` - just to confirm it's an ordinary Spring
   Boot app.
2. `merchant/ApiKeyAuthFilter.java` - how a request gets authenticated.
3. `PaymentsController.java` - where tokenization actually happens on the
   way in.
4. `idempotency/JpaIdempotencyStore.java` - why replaying the same request
   is safe.
5. `orchestrator/RestOrchestratorClient.java` - where the token, never the
   PAN, gets forwarded downstream.

## 3. Follow one payment across the wire

With `payments-edge` understood, trace a single `POST /v1/payments` across
the remaining three phase-1 services, in call order:

1. **`payment-orchestrator`** - the state machine, MySQL, and the
   transactional outbox row written in the same transaction as the state
   change.
2. **`psp-router`** - picks a provider: static priority in phase 1,
   health-weighted from phase 5 on.
3. **`psp-connector`** - calls the mock PSP. Everything resilience-related
   eventually attaches here.

Read [`docs/phases/01-vertical-slice.md`](phases/01-vertical-slice.md)
side-by-side with the code. It narrates exactly this path and the traps hit
building it - the docs and the code were written together, not after.

## 4. Then go phase by phase, not file by file

Once the vertical slice is solid, follow [`docs/phases/`](phases/) in order.
For each phase: read the doc first, then the code, then its
[`docs/experiments/`](experiments/) writeup. The graphs are the payoff - they
show *why* each remaining starter exists, instead of it appearing as a wall
of code with no motivation.

| Phase | Adds | What it's for |
|---|---|---|
| 2 | Chaos harness | The baseline failure report - break the undefended system first, on purpose |
| 3 | Resilience layer | `resilience-starter`, `chaos-core`. One component at a time, each after the experiment that justifies it |
| 4 | Observability | `observability-starter`. SigNoz, trace/log correlation, the PAN-leak build test |
| 5 | Health-driven routing | The differentiator, and the README's headline graph |
| 6 | Async spine | Kafka, the outbox relay, CDC, saga compensation |
| 7 | Concurrency & idempotency | `idempotency-starter`'s harder half - fingerprinting, in-flight waits |
| 8 | Data layer depth | Indexing, the UNKNOWN-poller, reconciliation |
| 9 | gRPC, security, data protection | mTLS, envelope encryption, KEK rotation, erasure |
| 10 | Packaging | README, ADRs, experiment writeups, the 90-second demo - this guide's siblings |

---

A version of this guide with a reading-path layout is published as a
[Claude artifact](https://claude.ai/code/artifact/248897b7-deda-47c2-92ec-90acfe03bfb2);
this file is the copy that lives with the code.
