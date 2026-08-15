# Phase 1 — Vertical slice (happy path)

| | |
|---|---|
| **Estimate** | ~2 weeks |
| **Depends on** | phase 0 |
| **Delivers** | one payment succeeding end to end, and the tokenization boundary |

## Goal

One payment succeeds end to end. **No resilience anywhere.**

That second sentence is a constraint, not an omission. No retries, no breakers,
no timeouts, no fallbacks.

## Why here

Phase 2 has to break this and watch it break. Every defensive measure added now
destroys the "before" half of a graph you have not drawn yet. If a timeout is
already in place when you run the baseline, you never see thread pools saturate,
and you lose the single most convincing artefact in the project.

Tokenization also has to land here rather than later, because it is the one
control that is genuinely impossible to retrofit. Once payment rows, log
archives and (from phase 6) Kafka topics contain raw PANs, you are not
tokenizing — you are running a data cleanup with an unbounded tail.

## Prerequisites

- Phase 0 complete; `core` profile healthy
- `V1__baseline.sql` applied (`flyway_schema_history` has one row)
- `uuid-creator` already in the version catalog, unused

## Implementation

### 1. The payment state machine

Plain enum plus an explicit transition table. **No state machine library** — the
whole value is being able to show the transition rules as data.

```
INITIATED → ROUTED → AUTHORIZING → AUTHORIZED → CAPTURED → SETTLED
                          ↓
                    FAILED / UNKNOWN
```

Model `UNKNOWN` now. A connector timeout does **not** mean the payment failed;
it means the outcome is not yet known. A background poller (phase 8) resolves it
into a terminal state. Systems that conflate "timed out" with "failed"
double-charge people, and this is the single most common design flaw in
home-built payment systems.

Enforce transitions centrally — an illegal transition should throw, not log.

### 2. MySQL schema — `V2__core_tables.sql`

`merchant`, `psp_config`, `payment`, `payment_attempt`, `idempotency_record`,
and the isolated `token_vault`.

**UUIDv7 primary keys, stored as `BINARY(16)`.**

The reason is InnoDB-specific: the primary key *is* the clustered index. UUIDv4's
randomness scatters inserts across the whole B-tree, causing page splits, poor
fill factor and index bloat that grows with table size. UUIDv7 is time-ordered,
so inserts append to the rightmost page.

Store as `BINARY(16)`, not `CHAR(36)`. 16 bytes versus 36, and every secondary
index in InnoDB carries a copy of the PK — so the choice multiplies across the
schema. It will also make phase 8's index measurements honest.

`token_vault` gets **its own database credentials**, not just its own table.
That separation is what makes the phase 9c access story real.

### 3. `payments-edge`

- `POST /v1/payments` (requires `Idempotency-Key`), `GET /v1/payments/{id}`
- API-key auth — hashed at rest; rotation hardens in phase 9b
- The API key must never reach a log line. It is not in `LogFields`, so
  `LogEvent` rejects it. Verify that rather than assume it.

### 4. Basic idempotency

Unique constraint on `(merchant_id, idempotency_key)` plus response replay.

Just those two. Body fingerprinting, Redis in-flight markers, 409 handling and
TTL expiry are phase 7 — they exist to solve concurrency problems you have not
observed yet.

**Store the rendered response bytes.** Do not re-serialize on replay and hope
the output matches; field ordering and timestamp formatting will differ. Exit
criterion 4 is byte-identical.

### 5. `payment-orchestrator`

Owns every transition. Calls the connector over **REST**.

REST first is deliberate: phase 9 migrates the same path to gRPC and benchmarks
both under identical load. That is a concrete data point most candidates only
have opinions about, and it only exists if you build REST first.

### 6. `psp-connector`

One adapter. **Zero resilience annotations.** Detokenizes at the last moment
before the provider call.

### 7. `mock-psp-simulator`

`authorize`, `capture`, `status`, plus the control endpoint:

```
POST /_chaos {latencyMs, errorRate, hangRate, duplicateRate}
```

Build this properly and make it runtime-reconfigurable with no restart. Every
experiment from phase 2 through phase 10 drives it. `hangRate` means *never
responds* — distinct from a slow response, and the one that finds missing
timeouts.

## Tokenization — PCI scope reduction

The core PII control.

| Rule | Detail |
|---|---|
| Single entry point | `payments-edge` is the only component that sees a raw PAN |
| Swap on arrival | PAN → token before anything else touches the request |
| Downstream carries | `bin(6) + token + last4`, nothing more |
| CVV | never stored. Not encrypted, not hashed, not logged. In transit, then discarded |
| Detokenization | `psp-connector` only, at the last moment |

This is how real PSPs shrink audit scope, and it is a common design-round
question you would have actually built.

**Be honest about the boundary.** Raw PAN exists in three places: `payments-edge`,
`token_vault`, and `psp-connector` at call time. Claiming "one component" invites
an interviewer to find the connector. Three components with one audited reversal
path is still an excellent story.

## Key decisions

| Decision | Why |
|---|---|
| Enum + transition table, no library | The rules are the interesting part; a library hides them |
| `UNKNOWN` as a first-class state | Timeout ≠ failure. Retrofitting means revisiting every transition and query |
| UUIDv7 as `BINARY(16)` | Clustered-index locality; PK size multiplies across secondary indexes |
| Store rendered response for replay | Byte-identical replay is not achievable by re-serialization |
| REST before gRPC | Makes the phase 9 benchmark possible |
| Separate credentials for `token_vault` | Turns "isolated" from a comment into a control |

## Exit criteria

- [x] `POST /v1/payments` with `Idempotency-Key` → 201, `INITIATED` row in MySQL
- [x] Orchestrator → connector → simulator → success; payment reaches `AUTHORIZED`
- [x] `GET /v1/payments/{id}` returns it
- [x] Replaying the same key returns a **byte-identical** body; exactly one row exists
- [x] Chaos endpoint responds and changes behaviour
- [x] Cold `docker compose up` plus a k6 smoke script passes
- [x] A `grep` of every table outside `token_vault` and of all captured log
      output finds **zero** Luhn-valid card numbers

Automate the last one now. It is the seed of the phase-4 PAN-leak build test,
and `Masking.isLuhnValid` already exists to power it.

## Traps

**The Boot 4 autoconfiguration split will recur.** It bit Flyway in phase 0 and
will bit whatever you add by bare artifact name — HTTP client, security,
validation. Symptom is always identical: no error, no log line, feature simply
absent. Reach for `spring-boot-starter-*` first, and verify the feature actually
did something rather than trusting a healthy container.

**Jackson 3 for DTOs.** Records with `@Sensitive` components already work
(there is a test), but any custom serializer targets `ValueSerializer`.

**`ddl-auto` is `validate`.** The moment you add an entity without a matching
migration, startup fails. That is intended — it is what keeps Flyway as the
single source of schema truth.

**Do not log the whole request object.** `LogEvent`'s allowlist is the control,
but a `log.debug("req={}", request)` bypasses the intent even if masking catches
the PAN. Log named fields.

## Interview payload

State machine design and why `UNKNOWN` must exist in any payment system.
UUIDv7 vs v4 as a clustered primary key. Tokenization as PCI scope reduction.

**Be ready for:** *"What happens if the connector times out after the provider
already authorized?"* That is exactly why `UNKNOWN` exists, why the status
poller in phase 8 exists, and why retrying an authorize on a *different*
provider (phase 5) is dangerous without provider-side idempotency keys.
