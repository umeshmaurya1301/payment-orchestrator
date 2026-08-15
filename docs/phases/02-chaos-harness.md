# Phase 2 — Chaos harness and load

| | |
|---|---|
| **Estimate** | ~1 week |
| **Depends on** | phase 1 |
| **Delivers** | `docs/experiments/00-baseline.md` — the "before" half of every later graph |

## Goal

Watch the system collapse, and record exactly how.

## Why here

**This is the phase that turns the project from "I added Resilience4j" into a
chaos engineering story. Do not skip it and do not shorten it.**

Everything from phase 3 onward is justified by a measurement taken here. Without
a baseline, every subsequent claim is an assertion. With one, every claim is a
graph with a before and an after.

There is also a one-way door: once a timeout exists, you can never again observe
what the system does without it, because you will not remove it to find out.

## Prerequisites

- Phase 1 complete; a payment reaches `AUTHORIZED`
- `mock-psp-simulator` chaos endpoint working and runtime-reconfigurable
- **No resilience components anywhere.** Verify this rather than assume it

## Implementation

### 1. k6 scripts — `tools/loadtest/`

| Script | Profile | Finds |
|---|---|---|
| `smoke.js` | 1 VU | Does a payment still work at all |
| `ramp.js` | constant arrival rate, 50 → 1000 rps | Where the knee is |
| `spike.js` | sudden 10× burst | Cold pools, queue buildup |
| `soak.js` | 30 min moderate load | Leaks and pool exhaustion |

Use **constant arrival rate**, not fixed VUs, for `ramp.js`. With fixed VUs a
system that slows down also receives less load — the generator quietly backs off
exactly when the interesting thing starts happening, and the collapse you are
trying to measure hides itself.

`soak.js` matters more than it looks. Connection leaks and slow pool exhaustion
are invisible in a 60-second run and are the most common cause of "it works in
dev, dies overnight".

### 2. Toxiproxy

Containers in front of **MySQL and Redis**. Services connect to the proxy port,
not the real one. Kafka gets a proxy in phase 6.

Phase 0 already made this a compose-only change: `ORCHESTRATOR_DB_URL` is driven
by environment, so repointing at Toxiproxy needs no code change.

Toxics to have ready: `latency`, `timeout`, `reset_peer`, `bandwidth`.

### 3. Spring Boot Chaos Monkey

Wired into each service, **disabled by default**, toggled via actuator. Version
4.0.0 is on Maven Central; confirm Boot 4 compatibility on first use — if it has
not caught up, replace this layer with `chaos-core` seams rather than pinning a
service to Boot 3.

### 4. `chaos-core` in `infra-core`

Thin, for the two faults off-the-shelf tools cannot reach cleanly:

- **Sleep inside a held `FOR UPDATE` lock** — a deterministic deadlock trigger,
  used in phase 7
- **Targeted exception injection on a specific `@KafkaListener`** — phase 6

Keep it genuinely thin. It is a test seam, not a framework.

### 5. Pumba

Process-level chaos: `kill`, `pause`, SIGTERM a whole container. The SIGTERM
path is what phase 7's graceful-shutdown work depends on — and phase 0's `exec`
in the Docker entrypoint is what makes it reach the JVM.

### 6. Metrics

Micrometer plus `/actuator/prometheus` (already exposed). Full SigNoz is phase 4;
k6 summary output plus raw actuator metrics is enough here.

Capture at minimum: **thread pool state, Hikari pool state, response time
percentiles, error rate, and JVM heap.**

## The deliberate break

Run each in isolation. **One fault at a time** — two active chaos sources means
you cannot attribute what broke.

| # | Setup | Hypothesis to write first |
|---|---|---|
| 1 | `ramp.js` to 500 rps, simulator 3000 ms latency on 100% of calls | Where does the queue form, and what saturates first? |
| 2 | `ramp.js` to 500 rps, simulator 40% error rate | Does error rate stay proportional, or amplify? |
| 3 | `ramp.js`, Toxiproxy +500 ms on MySQL | Hikari saturation, and what it does to unrelated endpoints |

Write the hypothesis **before** each run. The gap between prediction and reality
is the learning, and recall will quietly rewrite what you expected if you do not.

## Key decisions

**Five distinct chaos layers, not one tool.** They break different things and
are not interchangeable:

| Layer | Tool | Injects |
|---|---|---|
| Downstream PSP | `mock-psp-simulator` | Business-level: latency, errors, hangs, duplicates |
| Network / connections | Toxiproxy | Latency, timeout, `reset_peer`, bandwidth |
| In-process beans | Chaos Monkey | Generic latency and exceptions on beans |
| Bespoke seams | `chaos-core` | Sleep-in-held-lock, per-consumer exceptions |
| Process / container | Pumba | `kill`, `pause`, SIGTERM |
| Concurrency | k6 | The load that makes any of the above *chaotic* |

Toxiproxy breaks the **link**, Pumba breaks the **process**, Chaos Monkey breaks
the **bean**.

**Chaos without k6 concurrency is invisible.** A bulkhead only rejects when
in-flight calls exceed the limit; a breaker only trips on a *rate* of failures
within a window. Faults applied without load are merely faults.

## Exit criteria

- [x] All four k6 scripts run and produce comparable output
- [x] Toxiproxy in front of MySQL and Redis; services connect through it
- [x] ~~Chaos Monkey~~ **`chaos-core` bean assault** togglable at runtime, off by
      default. Chaos Monkey 4.0.0 is inert on Boot 4.1 and its endpoint cannot be
      reached; the layer was replaced rather than pinning a service to Boot 3,
      exactly as this page allowed for. See `docs/experiments/README.md`.
- [x] Pumba can kill, pause and SIGTERM a container (needs 1.1.7+; 0.11.6 speaks
      Docker API 1.42 and Engine 29 requires 1.44)
- [x] **`docs/experiments/00-baseline.md` written** - what broke, in what order,
      with numbers: connection pools, response times, error rates, heap. There
      are no thread-pool numbers because virtual threads mean there is no
      bounded thread pool to exhaust, and that absence turned out to be the
      finding rather than a gap in the capture.

The document is the deliverable. Tooling that produced no writeup has produced
nothing.

## Traps

**Fixed-VU load tests hide collapse.** Covered above; it is the single most
common load-testing mistake.

**Running two chaos sources at once.** Tempting when time is short, and it
destroys attribution. If you have one week, run three clean experiments rather
than six muddled ones.

**Measuring only the edge.** The interesting number is usually one hop in —
Hikari `pending` threads, or connector in-flight count. Edge latency tells you
something is wrong, not what.

**Forgetting to reset chaos between runs.** Bake a reset into the script's setup
stage rather than trusting yourself to remember.

## Interview payload

The baseline report itself. Being able to say "at 500 rps with a 3-second
downstream, here is the order in which things failed, and here is why the first
thing to saturate was not what I expected" is worth more than any list of
libraries.

**Be ready for:** *"Why not just add timeouts from the start?"* Because then you
never learn what the timeout is worth, cannot size it from data, and have no
evidence it helped.
