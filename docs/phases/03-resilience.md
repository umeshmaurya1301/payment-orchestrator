# Phase 3 — Resilience layer

| | |
|---|---|
| **Estimate** | 3-4 weeks |
| **Depends on** | phase 2 baseline report |
| **Delivers** | six experiment writeups, one per sub-step, and a populated `resilience-starter` |

## Goal

Each component added individually, measured individually.

## Why here

The baseline exists, so every component now has a "before". This is the phase
where the project's central discipline is visible: **do these strictly in order,
and run a k6 load test with a one-paragraph written result after each
sub-step.**

Adding all six at once produces a system that works and a story you cannot tell.
Adding them one at a time produces six graphs.

Everything lands as annotations in `infra-core/resilience-starter`, currently an
empty shell with working autoconfiguration registration.

## Prerequisites

- `docs/experiments/00-baseline.md` written
- All five chaos layers working
- `resilience4j-spring-boot4` 2.4.0 — note the **`spring-boot4`** artifact, not
  `spring-boot3`

## Implementation — strictly in order

### 3a. Time limiter + deadline budget

Not per-call timeouts. A **budget**.

The edge stamps a 30s deadline. Each hop decrements it by elapsed time and
propagates the remainder downstream. A connector receiving a request with 400 ms
remaining fails immediately rather than starting a 3s call.

Propagate over an HTTP header for now; phase 9 replaces this with gRPC metadata.
Doing it twice is deliberate — the comparison is the point.

**Why a budget beats per-call timeouts:** with per-call timeouts of 3s at each of
four hops, a request can legitimately take 12s while the client gave up at 5.
Every hop is individually within its limit and the whole thing is useless work.

### 3b. Retry

**Classification first.** Retryable vs non-retryable by error type. Never blind-retry
a non-idempotent authorize — that is how you double-charge.

- Exponential backoff with **full jitter**, not fixed or equal jitter.
- **Retry budget**: cap retries at ~10% of total traffic.

The budget is the part most candidates have never considered. Without it, a
partial outage becomes a self-inflicted retry storm: the downstream degrades,
every client retries, offered load triples, and the downstream that might have
recovered now cannot.

### 3c. Circuit breaker

Per provider **per operation**. `authorize` and `status` fail differently and
must trip independently — a provider whose authorize is down may still answer
status perfectly, and you need status to resolve `UNKNOWN` payments.

Decide and be able to defend: count-based vs time-based sliding window,
half-open permit count, minimum number of calls.

**Publish state changes as events.** Phase 5 consumes them as a routing input.

### 3d. Bulkhead

Per provider, sized to their contracted TPS.

Run an explicit **semaphore vs thread-pool experiment** and keep the numbers.
With virtual threads, thread-pool bulkhead is usually the wrong choice: you pay
platform-thread cost to isolate something that is already cheap. Use thread-pool
bulkhead only where you must isolate a genuinely blocking call.

### 3e. Rate limiters — three distinct layers

| Layer | Where | Mechanism |
|---|---|---|
| Ingress, per-merchant | `payments-edge` | Redis + Lua token bucket (atomic, distributed) |
| Per-endpoint | `payments-edge` | `/payments` and `/status` get different limits |
| **Egress, per-PSP** | `psp-connector` | Enforces *their* contracted TPS |

The Lua script matters: check-then-decrement across two round trips is not
atomic and over-admits under concurrency.

The **egress limiter** is the one most candidates have never considered. It
protects the downstream, not you — so you do not get blocked by a provider for
exceeding a contract.

### 3f. Dynamic config reload

Bulkhead limits, breaker thresholds and timeouts read from the `psp_config`
table and refreshed **without restart**. This is what makes "different config
per provider" real rather than a YAML file.

## Key decisions

| Decision | Defence |
|---|---|
| Deadline budget over per-call timeouts | Per-call timeouts compose into a total nobody bounded |
| Retry budget | Turns a partial outage into a recoverable one rather than an amplified one |
| Full jitter | Equal/decorrelated jitter still synchronises retry waves |
| Breaker per provider **per operation** | authorize and status have independent failure modes |
| Semaphore bulkhead by default | Under virtual threads, thread-pool isolation pays platform-thread cost for nothing |
| Config from DB, not YAML | Provider limits change without a deploy |

## Exit criteria

- [ ] Three providers configured with deliberately different personalities:
      **A**: 99.9% / 200 ms / 500 TPS · **B**: 96% / 2.5 s / 50 TPS ·
      **C**: 98% / 800 ms / 200 TPS
- [ ] Each with its own resilience config, all loaded dynamically from `psp_config`
- [ ] Config change takes effect without restart, demonstrated
- [ ] **Six experiment writeups in `docs/experiments/`** — one per sub-step

## Traps

**Adding two components before measuring.** The most likely way this phase goes
wrong. If 3b and 3c land together you cannot say what the breaker was worth.

**Retrying non-idempotent operations.** Classification must come before the
retry mechanism, not after.

**Breaker thresholds tuned by feel.** Use the baseline numbers. A breaker that
trips at 50% failure when the baseline error rate is 40% under load will
flap continuously.

**Semaphore bulkhead with blocking I/O and virtual threads.** Before Boot 4's
virtual threads, a blocking call pinned a platform thread. Verify what your HTTP
client actually does — if it pins carrier threads, the semaphore reasoning
changes.

**Testing resilience without concurrency.** A breaker needs a *rate* of failures
in a window. A single failing curl proves nothing.

## Interview payload

Semaphore vs thread-pool bulkhead under virtual threads, with numbers. Retry
budget vs naive retry. Deadline budget vs per-call timeout. Egress rate limiting
as a concept.

**Be ready for:** *"Your breaker opened — now what?"* If the answer is "we return
an error", the design is incomplete. Phase 5 makes the answer "routing weight
drops and traffic drains to another provider", which is a materially better
answer and the reason 3c publishes state-change events.
