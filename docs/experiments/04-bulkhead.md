# 04 — Bulkhead: semaphore vs thread pool (phase 3d)

The fourth component, measured on its own. 3a's deadline, 3b's retry and 3c's
breaker were in place and unchanged for every arm.

This is the first component in phase 3 whose experiment did **not** justify it on
the terms it was proposed on. It is written up that way.

> **Headline.** The bulkhead does exactly what it claims — provider load fell
> **92%** (11,621 → 937 requests) and connector heap **64%** (293 → 106 MiB).
> But at 200 rps against a merely *slow* provider it turned a system that was
> serving **100% of payments** into one serving **6.8%**, because a limit sized
> by Little's law from healthy latency becomes a hard throughput ceiling the
> moment latency degrades. And at the 500 rps that actually kills this system, it
> **did not prevent the crash it was added to prevent**: `payments-edge` still
> ran out of heap, because the resource that dies is consumed at admission, three
> services upstream of the bulkhead.
>
> Semaphore beats thread pool: same admission outcome, **4× better tail**
> (p99 3.30 s vs 12.23 s), 107 MiB less upstream heap, and 20 fewer platform
> threads.

---

## Hypothesis

Written before the runs.

> The bulkhead is the first component that bounds *how many* rather than *how
> long*. I expect it to cap in-flight calls at 20 per provider, hold connector
> heap flat, and convert the excess into prompt `bulkhead_full` declines instead
> of a growing queue.
>
> On the implementation question I expect the semaphore to win clearly under
> virtual threads: no handoff, no platform threads, no lost thread-bound context.
> I expect the thread pool to cost roughly one platform thread per permit for no
> measurable benefit.
>
> **Least confident about:** whether 20 is the right number. It comes from
> Little's law on the *healthy* profile — 500 TPS × 40 ms — and I do not know
> what it does when latency is 75× that.

The last paragraph turned out to be the whole experiment.

---

## Setup

| | |
|---|---|
| Fault | `mock-psp-simulator` `latencyMs: 3000` — slow, not broken |
| Load | `ramp.js`, 25 s stages, at both **200 rps** and **500 rps** |
| Bulkhead | 20 concurrent per provider, 250 ms max wait; thread-pool arm adds a 50-deep queue |
| Control | `BULKHEAD_LIMIT=100000` — present but unreachable |
| Variable | the bulkhead only |

`latencyMs: 3000` rather than an outage, because a bulkhead is a statement about
*concurrency*, and only a slow provider grows concurrency. A provider returning
errors quickly never accumulates in-flight calls, and 3c's breaker already owns
that case.

All five arms were run against one build, back to back. That is not
housekeeping: the first version of this table mixed two builds, and the same
semaphore configuration produced p95 = 32.06 s in one and 3.22 s in the other.
The difference was entirely upstream saturation left over from the previous run.
A number carried across a rebuild is not a measurement.

---

## Result — 200 rps

| | Before (unbounded) | **Semaphore (20)** | Thread pool (20 + 50) |
|---|---|---|---|
| **Payments `AUTHORIZED`** | **11,526** | **840** | **870** |
| Payments `FAILED` (`bulkhead_full`) | 0 | 11,534 | 11,504 |
| Success rate | **100%** | 6.79% | 7.03% |
| Dropped iterations | 848 | 0 | 0 |
| median | 3.05 s | **0.29 s** | 0.29 s |
| p95 | 8.55 s | **3.22 s** | 9.30 s |
| p99 | 11.20 s | **3.30 s** | 12.23 s |
| max | 12.43 s | **3.37 s** | 14.05 s |
| Peak k6 VUs | 1,160 | **81** | 151 |
| **Requests reaching the provider** | 11,621 | **937** | 971 |
| Connector heap | 293 MiB | **106 MiB** | 106 MiB |
| Orchestrator heap | 294 MiB | **188 MiB** | 295 MiB |
| Orchestrator Hikari pending | 568 | **0** | 51 |
| Edge Hikari pending | 521 | **0** | 39 |
| Connector platform threads | 92 | **73** | 105 |
| — of which bulkhead pool | 0 | 0 | 20 |

Every claim the component makes is in that column and every one of them holds.
Provider load −92%. Connector heap −64%. Hikari pending 568 → 0. Peak concurrency
1,160 → 81. The tail is bounded at 3.37 s, which is one provider call and not a
millisecond more — nothing waits behind anything.

And it is still the wrong outcome. **The control arm authorised 11,526 payments
and the bulkhead arm authorised 840.**

### Why the control arm did not die

This is the part worth sitting with. At 200 rps with a 3 s provider, the system
needs 200 × 3 = 600 concurrent in-flight payments. On virtual threads, 600
concurrent payments is *not a problem* — the whole chain carried them at 100%
success, with the only cost being latency that honestly reflected the provider's
own. The phase-2 baseline died at 500 rps, not 200.

So at this load the bulkhead was not protecting against anything. It was the only
thing failing.

### The sizing trap

20 permits comes from Little's law applied to the healthy profile:

```
L = λ × W  =  500 TPS × 0.04 s  =  20
```

That arithmetic is correct and the conclusion is a trap, because `W` is not a
constant — it is the thing that degrades. Re-run it at the observed latency:

```
throughput ceiling = L / W = 20 / 3 s = 6.67 rps
```

At 200 rps offered, a 6.67 rps ceiling means **96.7%** of traffic must be
refused. Measured: 93.2% refused. The component behaved exactly as specified and
the specification was a mass-decline event.

A static concurrency limit derived from healthy latency has this property
structurally: it converts *any* sustained latency degradation into a proportional
outage, and the worse the degradation the more complete the outage. A provider
that gets 75× slower does not lose you 75× throughput — it loses you everything
above 1/75th.

This is not an argument against bulkheads. It is an argument that the limit
cannot be a constant, which is [phase 3f](../phases/03-resilience.md)'s dynamic
config, and that a degraded provider's traffic belongs somewhere else entirely,
which is phase 5's routing. 3d's job was to find that out by measurement, and it
did.

---

## Result — 500 rps, the load that actually kills this system

The 200 rps arms measure the cost. This pair measures the benefit, at the load
where phase 2 recorded the deaths.

| | Before (unbounded) | **Semaphore (20)** |
|---|---|---|
| **`payments-edge` `OutOfMemoryError`** | **2** | **1** |
| Payments `AUTHORIZED` | 13,493 | 580 |
| Payments `FAILED` (`bulkhead_full`) | 0 | 10,327 |
| **Transport failures** (no HTTP answer at all) | **8,911** | **7,563** |
| Dropped iterations | 7,594 | 11,527 |
| Success rate | 60.23% | 3.14% |
| p99 | 21.79 s | 25.43 s |
| **Requests reaching the provider** | **16,228** | **724** |
| Connector heap | 288 MiB | **89 MiB** |
| Edge Hikari pending | 606 | 937 |

### The bulkhead did not save the edge

`payments-edge` threw `OutOfMemoryError: Java heap space` in **both** arms — twice
without the bulkhead (06:53:55, 06:54:23) and once with it (06:58:08), each
followed by a restart. Reducing two crashes to one is not protection; it is the
same failure arriving slightly later.

The reason is siting, and in hindsight it is obvious. The bulkhead is on the
**PSP call**, in `psp-connector`. The resource that runs out belongs to
`payments-edge`, and it is consumed at **admission** — a request thread, a Hikari
connection, a parsed body, an idempotency row — all allocated before the edge has
any idea whether the connector will accept the call. Refusing at the connector
frees the connector. It does nothing for the three tiers that already paid.

Edge Hikari pending is the tell: **937 with the bulkhead against 606 without.**
The bulkhead made upstream queueing *worse*, because declining in 250 ms instead
of answering in 3 s let k6 offer load faster into a tier that was already the
constraint.

### What it did buy

One thing, and for a payment system it is not a small thing: **provider requests
fell from 16,228 to 724.** In the control arm, 8,911 clients received no answer
for payments that *had been dispatched to the provider* — every one of those is a
card that may have been charged with no record of the outcome. In the bulkhead
arm the 7,563 unanswered clients were overwhelmingly requests that were **never
sent anywhere**. Same count, categorically different liability.

That distinction only holds because `BulkheadFullException` means *nothing was
sent* — which is exactly the invariant the implementation broke, below.

---

## Semaphore vs thread pool

The stated deliverable for 3d, with one variable: same limit, same load, same
fault, same build.

| | Semaphore | Thread pool |
|---|---|---|
| Authorized | 840 | 870 (+3.6%) |
| p99 | **3.30 s** | 12.23 s (**3.7×**) |
| max | **3.37 s** | 14.05 s |
| Orchestrator heap | **188 MiB** | 295 MiB (+107 MiB) |
| Orchestrator Hikari pending | **0** | 51 |
| Platform threads (connector) | **73** | 105 |
| Platform threads (bulkhead itself) | **0** | **20** |

The extra 30 authorisations are inside run-to-run noise. The 8.9 s of added tail
is not, and it is not a mystery — it is the queue, doing arithmetic:

```
queue depth 50 ÷ 20 permits × 3 s per call  =  7.5 s of queueing
                                       + 3 s of work  ≈  10.5 s
```

Measured p99: 12.23 s. The queue is not absorbing a burst; it is a latency
amplifier that holds 50 requests upstream — with their edge threads and their
Hikari connections — so that they can be served after they have stopped being
worth serving. That is the phase-2 pathology in miniature, reintroduced by the
component added to prevent it, and it shows up directly as the +107 MiB of
orchestrator heap and 51 pending connections that the semaphore arm simply does
not have.

The 20 platform threads are the honest sticker price, reported by
`payorch_bulkhead_platform_threads` and confirmed exactly. Under virtual threads
they buy nothing: the semaphore isolates the same 20 concurrent calls at zero
platform-thread cost, on the caller's own thread, with the stack trace and the
`ScopedValue` deadline intact.

**Conclusion: semaphore, and it is not close.** The thread pool earns its cost
only when the call *pins* its carrier thread — a JNI call, or a library that
synchronizes around blocking I/O — because then the carrier is the resource
actually being exhausted and a semaphore isolates nothing. No call in this system
does that. The implementation stays in the tree, selectable by
`BULKHEAD_KIND=threadpool`, because the argument for the default is worth more
when the alternative is runnable.

---

## The defect this experiment found

The first thread-pool run reported 0 successes, 12,374 declines, and a flat
~340 ms response to everything, while the provider still received all 12,458
requests. Neither shed nor served.

The cause was one line:

```java
future.get(waitMs, TimeUnit.MILLISECONDS);   // waitMs = maxWaitMs = 250
```

`SemaphoreBulkhead`'s `maxWaitMs` bounds the wait for a **permit**, after which
the work runs under the request's deadline. That single `get` bounded **queue
wait plus execution together**. With a 250 ms ceiling and a 3 s provider, every
call was dispatched, sent, and abandoned 250 ms later. The two classes were not
implementing the same contract, so the comparison was measuring two timeout
policies rather than two isolation mechanisms.

The performance was the least of it. It threw `BulkheadFullException` *after the
request had gone out* — and that exception's contract is **nothing was sent**,
which `psp-connector` maps to a 503 and `PaymentService` records as a definite
`FAILED`. So 12,374 payments were written down as declined while the provider was
busy authorising the cards. That is precisely the `FAILED`-vs-`UNKNOWN` collapse
[phase 3a](01-deadline-budget.md) exists to prevent, reintroduced two sub-phases
later by a misplaced timeout, and no test caught it because every test used work
that completed instantly.

The fix separates the two clocks. Admission is a timed
`ArrayBlockingQueue.offer` and can only fail *before* the task exists, so
`BulkheadFullException` is honest by construction. Waiting for an admitted task
is bounded by the deadline, and giving up on one throws
`DeadlineExceededException` carrying an `AtomicBoolean` the pool thread flips
immediately before the work runs — so `wasStarted()` reports whether the provider
was actually contacted rather than guessing.

Two parameterised regression tests now hold both implementations to the same
contract:

- `workSlowerThanTheWaitCeilingStillCompletes` — a call slower than `maxWaitMs`
  must succeed. This is the one that would have caught it.
- `rejectionMeansTheWorkNeverRan` — `BulkheadFullException` must imply the work
  was never dispatched.

The general lesson is about the shape of the bug rather than the bug: two
implementations behind one interface, tested only with instant work, agreed on
every test and disagreed on the only thing that mattered. The contract said
"nothing was sent" in a Javadoc and nowhere in an assertion.

---

## What this changes

1. **The bulkhead ships, as a semaphore, and its limit is provisional.** It bounds
   unbounded concurrency growth, which is real and which nothing else in phase 3
   does. It is not a defence against latency degradation.
2. **A static limit sized from healthy latency is a latent outage.** 3f's dynamic
   config is no longer a nice-to-have; the limit has to move with observed
   latency, or the component is a scheduled incident.
3. **Concurrency limiting at the PSP call cannot save the ingress tier.** The edge
   OOM'd anyway. Admission control has to be *at admission* — which is exactly
   what [3e](../phases/03-resilience.md) is, and this experiment is the reason to
   build the per-merchant ingress limiter before anything else in the phase.
4. **A degraded provider needs somewhere else to go.** 93% declines is the correct
   behaviour of a correct bulkhead against a provider that should have been
   routed away from. Phase 5.
5. **Phase-2 task #26 is now load-bearing.** `payments-edge` metrics went missing
   for most of the 500 rps run because `/actuator/prometheus` shares the
   application Hikari pool and was starved with everything else. An observability
   endpoint that fails exactly when observed is worse than none.

---

## Reproducing

```bash
# 200 rps — the cost
BULKHEAD_LIMIT=100000 docker compose up -d
CHAOS_LATENCY_MS=3000 MAX_RATE=200 STAGE_DURATION=25s \
  bash tools/loadtest/run-experiment.sh 3d-01-before-no-bulkhead ramp.js

BULKHEAD_KIND=semaphore BULKHEAD_LIMIT=20 docker compose up -d
CHAOS_LATENCY_MS=3000 MAX_RATE=200 STAGE_DURATION=25s \
  bash tools/loadtest/run-experiment.sh 3d-02-semaphore ramp.js

BULKHEAD_KIND=threadpool BULKHEAD_LIMIT=20 docker compose up -d
CHAOS_LATENCY_MS=3000 MAX_RATE=200 STAGE_DURATION=25s \
  bash tools/loadtest/run-experiment.sh 3d-03-threadpool ramp.js

# 500 rps — the benefit
BULKHEAD_LIMIT=100000 docker compose up -d
CHAOS_LATENCY_MS=3000 MAX_RATE=500 STAGE_DURATION=25s \
  bash tools/loadtest/run-experiment.sh 3d-04-before-500rps ramp.js

BULKHEAD_KIND=semaphore BULKHEAD_LIMIT=20 docker compose up -d
CHAOS_LATENCY_MS=3000 MAX_RATE=500 STAGE_DURATION=25s \
  bash tools/loadtest/run-experiment.sh 3d-05-semaphore-500rps ramp.js

# the edge deaths, which no k6 metric reports:
docker compose logs -t payments-edge | grep OutOfMemoryError
```

Restart `payments-edge` between the 500 rps arms — it does not come back healthy
on its own, and a run started against a degraded edge measures the degradation.
