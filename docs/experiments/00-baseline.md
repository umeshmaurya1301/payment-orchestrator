# 00 — Baseline: how phase 1 fails

The "before" half of every graph from phase 3 onward. Predictions were written
in [`hypotheses.md`](hypotheses.md) before any run and are quoted here verbatim,
including the ones that were wrong.

> **Headline.** The system does not need chaos to fall over. At 200 rps with a
> healthy downstream it already runs 1,037 threads queued for 20 database
> connections and a p95 of 13.2 s; at 500 rps `payments-edge` exhausts its heap
> and the process dies. Every fault injected afterwards is a variation on that
> one theme: **virtual threads removed the ceiling that used to reject work, so
> the queue moved into the heap.**

---

## Setup

| | |
|---|---|
| Build | phase 1 as delivered — no timeouts, retries, breakers, bulkheads or fallbacks |
| Host | Windows 11, Docker Engine 29.1.3, all services in one compose stack |
| Per service | 640 MB container limit, `-XX:MaxRAMPercentage=70` → ~448 MB heap |
| Hikari | 20 connections, `payments-edge` and `payment-orchestrator` |
| Threads | virtual threads enabled on every service |
| Load | `ramp.js`, constant arrival rate, 5 × 30 s stages to the stated peak; `spike.js` and `soak.js` as noted |
| Capture | `capture-metrics.sh`, `/actuator/prometheus` every 2 s |

Call chain for one payment:

```
k6 → payments-edge → payment-orchestrator → psp-connector → mock-psp-simulator
        (MySQL)          (MySQL)              (MySQL vault)
```

Toxiproxy sits in front of MySQL in **every** run including the controls, so the
extra hop is a constant that cancels out rather than a confound that only
appears when a toxic does.

### Two caveats, stated up front

**The load generator is part of the measurement.** k6 reports
`dropped_iterations` when it cannot start an iteration at the offered rate. At
200 rps that was 1,300 of ~14,800 (9%); at 500 rps it was 15,264 of ~36,000
(42%). A run with 42% drops is not a 500 rps run, so **the three fault
experiments were run at 200 rps, not the 500 rps in the phase plan**. The knee
is far below 500 anyway — see experiment 0 — so 200 rps is comfortably past it
and the comparison between runs stays clean, which matters more than matching a
number chosen before any measurement existed.

**Every run starts from restarted services.** Otherwise a heap left full by the
previous experiment becomes the next experiment's finding.

---

## Experiment 0 — the control, and the first real finding

No chaos. Ramp to 200 rps.

### Result

| | |
|---|---|
| Offered / achieved | 200 rps / **81 rps** |
| Success | **100%** — every payment `AUTHORIZED` |
| Latency | med 440 ms, p90 10.9 s, p95 13.2 s, max 25.2 s |
| Hikari pending | edge **999**, orchestrator **1,037** |
| Hikari active | 20 / 20 on both — fully saturated |

```
payment-orchestrator :: hikaricp_connections_pending
peak 1,037 over 194s

    1,037 |                                                  #
      864 |                                               # ##
      691 |                                              ## ##
      518 |                                             ### ##
      346 |                                            #### ##       #
      173 |                                         ## #### ##      ##
        0 +--------------------------------------------------------------------
          0s                                                            194s
```

Then the same run at 500 rps:

| | |
|---|---|
| Success | **49.7%** |
| Transport failures | 10,439 — no HTTP response at all |
| p95 | 20.8 s |
| Hikari pending, edge | **1,824** |
| `payments-edge` | **`Terminating due to java.lang.OutOfMemoryError: Java heap space` — twice** |

### What this means

A 100% success rate is doing a lot of hiding here. Every payment succeeded, and
a fifth of them took over 10 seconds to do it. The system was already past its
knee at a rate the plan treated as a warm-up.

The mechanism is the same in both runs and it is the thesis of this whole
document:

1. Virtual threads mean the web tier never refuses work. There is no bounded
   pool of request threads to fill and therefore nothing to produce the
   classic "thread pool exhausted" symptom.
2. The first genuinely bounded resource is the Hikari pool, at 20 connections.
3. So requests park. 1,037 of them, each a virtual thread holding a request
   context, a parsed body and a partially built response.
4. Parked virtual threads are cheap in scheduling terms and **not** cheap in
   memory terms. The queue is not in a pool; it is in the heap.
5. At 500 rps the heap runs out, `-XX:+ExitOnOutOfMemoryError` does exactly what
   it was configured to do, and the process dies.

`hikaricp_connections_pending` is the number that says all of this, and nothing
visible from the edge does. k6 saw a 100% success rate.

---

## Experiment 1 — downstream at 3000 ms

`mock-psp-simulator` delayed 3 s on 100% of calls. Ramp to 200 rps.

### Hypothesis (written first)

> I expect the order to be: 1. `psp-connector` accumulates in-flight requests
> […] 2. `payment-orchestrator`'s Hikari pool saturates […] 3. Throughput
> flattens well below 500 rps and end-to-end latency climbs past 3 s.
>
> **Least confident about:** whether the orchestrator's connection pool
> actually saturates.

### Result

| | Control | Experiment 1 |
|---|---|---|
| Success | 100% | **73.3%** |
| Transport failures | 0 | **3,000** |
| p95 | 13.2 s | **28.6 s** |
| Hikari pending, edge | 999 | 1,017 |
| Hikari pending, orchestrator | 1,037 | **63** |
| `psp-connector` heap | 76 MiB | **292 MiB** |
| `mock-psp-simulator` heap | 66 MiB | **286 MiB** |

```
psp-connector :: jvm_memory_used_bytes [area="heap"]
peak 374 MB over 184s

374 MB |                                                          #
312 MB |                                      #        # #     # ##   # ##
250 MB |                                   # ##     # ## # # # # ## ### ## #
187 MB |                              ## ### #####  # ## # # # # ## ### ## #
125 MB |        ### ###    #### ######## ### #####  # ## # # # # ## ### ## #
 62 MB |    ####### ###   ##### ######## ### #####  # ## # # # # ## ### ## #
     0 +--------------------------------------------------------------------
       0s                                                            184s
```

### What surprised me

**The orchestrator's connection pool got *less* contended, not more — 1,037
pending in the control, 63 with the fault applied.** I had this backwards, and
had flagged it as the thing I was least sure of.

The reason is the transaction boundaries. `PaymentPersistence` deliberately
closes its transaction before the connector call and opens a new one after, so a
payment waiting three seconds on a provider is holding **no** database
connection while it waits. Slowing the provider down therefore *removes* load
from the orchestrator's pool: fewer payments per second reach the database at
all.

That is a design decision from phase 1 paying off in a way I did not predict,
and it is only visible because the control run existed to compare against. It
also means the phase-3 instinct — "put a timeout on the connector call to protect
the database pool" — is aimed at a problem that does not exist. The database pool
was never the victim here.

The queue moved instead of growing. It went to `psp-connector`, whose heap went
from 76 MiB to 292 MiB holding three-second calls, and to `payments-edge`, which
OOMed and restarted once. The simulator's own heap also quadrupled, which is
worth noting as an instrument effect rather than a system finding.

---

## Experiment 2 — downstream at 40% errors

`mock-psp-simulator` returning 500 on 40% of calls. Ramp to 200 rps.

### Hypothesis (written first)

> Error rate stays roughly proportional. There is nothing in the system that
> could amplify it: no retries […] and no circuit breaker […] I expect
> approximately 40% of payments to end in `UNKNOWN` and 60% in `AUTHORIZED`,
> and I expect **latency to improve**.

### Result

| | Control | Experiment 2 |
|---|---|---|
| `AUTHORIZED` | 13,549 (100%) | 7,865 (59.6%) |
| `UNKNOWN` | 0 | **5,322 (40.4%)** |
| Transport failures | 0 | 0 |
| p95 | 13.2 s | **11.1 s** |
| Hikari pending, orchestrator | 1,037 | 1,149 |

Injected 40%, observed 40.4%. Latency improved by 2.1 s at p95. Both predictions
correct, and the run is duller than the other two — which is the correct
outcome for a system with no retry and no breaker to amplify anything.

### What surprised me

Nothing about the ratio. What is worth stopping on is **what those 5,322
payments are**.

They are not failures. Every one of them is a payment in state `UNKNOWN`: the
provider may have authorised the card and lost the response on the way back.
There is no poller yet — that is phase 8 — so they simply accumulate, at roughly
**30 per second**, and nothing in the system will ever resolve them.

The clean 40.4% is therefore the least interesting number on the page. A run
where "the error rate stayed proportional" is a run that quietly produced five
thousand payments nobody can account for, and the metric that says so is a state
counter, not an error rate. This is the argument for `UNKNOWN` existing as a
first-class state, made in numbers rather than in prose.

It is also the argument phase 3 has to answer carefully: the obvious fix is a
retry, and a retry against a payment whose outcome is unknown is how one charge
becomes two.

---

## Experiment 3 — MySQL +500 ms via Toxiproxy

A latency toxic on the MySQL proxy, downstream, 500 ms, `toxicity 1.0`. Ramp to
200 rps.

### Hypothesis (written first)

> A single manual payment with this toxic applied took **25.7 s** against a
> ~50 ms baseline while the whole system was otherwise idle — a ×500
> amplification […] I expect […] `/actuator/health` on the edge to be affected
> too […]
>
> **Least confident about:** the health endpoint. It should be unaffected — it
> does no database work. If it is affected anyway, that is the most interesting
> result in the whole baseline, and I do not currently have an explanation for
> it.

### Result

| | Control | Experiment 3 |
|---|---|---|
| Success | 100% | **0%** |
| `AUTHORIZED` | 13,549 | **0** |
| Rejected (4xx/5xx) | 0 | 3,513 |
| Transport failures | 0 | **7,618** |
| p95 | 13.2 s | **60.0 s** (k6's own ceiling) |
| Requests reaching the simulator | 13,646 | **362** |
| Hikari pending, edge | 999 | **2,896** |
| `/actuator/health`, edge | ~2 ms | **median 10.0 s** (probe timeout) |

Not one payment succeeded. Throughput to the provider fell by 97%.

### What surprised me

**The health endpoint was starved, and the reason is that it is not independent
of the database.**

I predicted it would be unaffected because "it does no database work". It does:

```
$ curl -s localhost:8080/actuator/health | jq '.components | keys'
["db", "diskSpace", "livenessState", "ping", "readinessState", "ssl"]
```

Spring Boot auto-configures a `db` health contributor whenever a `DataSource`
bean exists. It runs a validation query — and to run it, it must first **borrow a
connection from the same 20-connection Hikari pool that 2,896 application
threads are already queued for**. The health check joins the back of that queue.

The consequences chain in a way none of them individually suggest:

1. MySQL gets slow.
2. The application pool saturates.
3. `/actuator/health` cannot get a connection and times out.
4. Docker's healthcheck (5 s interval, 3 s timeout, 20 retries) starts failing.
5. The container is restarted — throwing away every in-flight request.
6. The restarted container reconnects to the same slow database and repeats.

A degraded dependency becomes a restart loop, and the thing that converted one
into the other is a health check that was trying to be helpful. This is the most
useful single result in the baseline, and I only have it because the prediction
was written down first and was wrong.

The ×500 amplification also held. Roughly 25–50 round trips per payment across
three services — Flyway's lock check, the API-key lookup, the idempotency claim,
the vault insert, the payment insert, the attempt insert, the state updates, the
vault read — and a 500 ms tax on every one of them.

---

## Experiment 4 — spike, 20 → 200 rps step

| | |
|---|---|
| Success | 66.8% |
| Transport failures | 4,985 |
| p95 | **41.9 s** |
| Hikari pending, edge | 1,785 |
| `payments-edge` | restarted mid-run |
| Dropped iterations | **20,222** |

The step to 200 rps produced a worse p95 (41.9 s) than the *ramp* to the same
200 rps (13.2 s). Same peak rate, three times the latency, because a ramp lets
the pool and the JIT warm up on the way and a step does not.

A system that passes a ramp to a given rate has not been shown to survive a step
to it. That is the entire reason the two profiles are separate files.

---

## Experiment 5 — soak, 20 rps for 30 minutes

Deliberately well below the knee. A soak at the knee measures the knee; the
question here is whether the system stays healthy doing something easy for
long enough to expose what a two-minute run cannot.

### Result

| | |
|---|---|
| Duration / rate | 30 min, 20 rps sustained, 36,001 iterations |
| Success | **99.99%** — 36,000 `AUTHORIZED`, 1 `UNKNOWN` |
| Latency | med **35 ms**, p90 43.5 ms, p95 46.8 ms, p99 58.9 ms |
| Hikari pending | **0 throughout**, on both services |
| Hikari idle | 19–20 of 20, all run |
| **Max** | **13 m 1 s** |

Heap and thread counts, first quartile against third — the check for a leak:

| Service | Heap q1 → q3 | Threads q1 → q3 |
|---|---|---|
| `payments-edge` | 14 → 13 MiB | 65 → 65 |
| `payment-orchestrator` | 10 → 17 MiB | 68 → 70 |
| `psp-connector` | — | 68 → 68 |

### What surprised me

**No leak, and one thirteen-minute request.**

The leak question answers itself cleanly: heap and thread counts are flat across
the whole 30 minutes, `hikaricp_connections_pending` never leaves zero, and 19
of 20 connections sit idle. Below the knee this system is genuinely healthy, and
p99 is 59 ms. That matters as much as the collapse findings — it establishes
that everything in experiments 0–4 is saturation, not a defect that would show
up anywhere.

Then there is the maximum. One request out of 36,001 took **13 minutes and 1
second**, and returned `UNKNOWN`. p99 was 59 ms; that single request is roughly
**13,000× p99**.

I cannot fully explain it, and the honest thing is to say so rather than invent
a mechanism. What can be ruled out: it was not slow *inside* any service.
Server-side `http_server_requests_seconds_max` never exceeded 2.4 s at the edge,
1.6 s at the orchestrator, 0.6 s at the connector, at any point in the run. So
the time was not spent in a request handler — it was spent waiting on a
connection that nothing was ever going to give up on.

Which is the point. **There is no timeout anywhere in this system**, so there is
no upper bound on how long a single stalled socket can hold a request open. A
two-minute run cannot find this; a thirty-minute run found exactly one. At
production volumes "one in 36,000" is not rare, and each one holds a virtual
thread, a request context and a database connection for as long as it lasts.

This is the strongest possible argument for the phase-3 deadline budget, and it
is an argument no amount of ramping produced.

### A second thing the soak exposed: stranded payments

After all six runs, the `payment` table holds:

| State | Rows |
|---|---|
| `AUTHORIZED` | 97,464 |
| `UNKNOWN` | 5,324 |
| **`AUTHORIZING`** | **56** |
| **`INITIATED`** | **68** |

`AUTHORIZING` and `INITIATED` are not terminal states. Those 124 payments were
mid-transition when `payments-edge` exhausted its heap and the process died, and
**nothing in the system will ever move them again**. They are not `UNKNOWN` —
they were never even given the state that means "we do not know". They are
simply stuck.

The 5,324 `UNKNOWN` rows are a known, designed-for problem with a phase-8 answer.
The 124 stranded rows are not: no poller looks for them, because a payment stuck
in `AUTHORIZING` is not a case anyone designed for. Crash recovery is now on the
list for phase 8 alongside the status poller, and it is on the list because a
soak run put it there.

## `payments-edge` heap exhaustion, per run

Counted from the container log: five `Terminating due to
java.lang.OutOfMemoryError: Java heap space` lines, and twelve
`Started PaymentsEdgeApplication` lines against six deliberate restarts.
Cross-checked against the request counter, which resets when the process dies -
a run whose `http_server_requests_seconds_count` delta is far below its
neighbours' is a run in which the edge restarted.

| Run | Edge request delta | Heap exhaustion |
|---|---|---|
| Control, 200 rps | 13,643 | no |
| Control, 500 rps | — (counter reset) | **yes, twice** |
| 1 — downstream 3000 ms | **1** | **yes** |
| 2 — downstream 40% errors | 13,273 | no |
| 3 — MySQL +500 ms | **2,611** | **yes** |
| 4 — spike 20 → 200 rps | **2,918** | **yes** |

The only two runs the edge survived are the two where requests kept *completing*
— the healthy 200 rps control, and the error run, where 40% of calls returned
early. Every run in which requests piled up rather than finishing killed it.
That is the clearest statement of the whole baseline: this system does not
degrade under pressure, it accumulates until it dies.

## What breaks, in order

1. **`hikaricp_connections_pending` goes positive** — within seconds, at rates
   far below anything that looks like load. This is the leading indicator and
   nothing at the edge shows it.
2. **Latency percentiles separate from the median.** Control: median 440 ms,
   p95 13.2 s. A 30× spread between typical and bad.
3. **Heap fills with parked requests.** Not with a leak — with work that has
   been accepted and cannot proceed.
4. **The JVM exits.** `OutOfMemoryError`, then a restart that drops everything
   in flight.
5. **The health check fails**, because it queues for the same pool, and the
   container gets restarted for being slow rather than broken.

## What phase 3 has to fix, and in what order

Written now, from the data, rather than from a list of libraries:

| Priority | Component | Justified by |
|---|---|---|
| 1 | **Bounded admission at the edge** (bulkhead / concurrency limit) | Experiment 0. The system accepts unlimited work and dies of it. A queue that rejects is strictly better than a queue that OOMs. |
| 2 | **A separate Hikari pool, or a dedicated connection, for health checks** | Experiment 3. Otherwise a slow database restarts the containers. |
| 3 | **Timeouts everywhere, deadline-budgeted from the edge** | Experiments 1 and 3. Nothing currently gives up, so work accumulates until memory runs out. |
| 4 | **Circuit breaker on the connector** | Experiment 1. 3,000 transport failures while the provider was merely slow. |
| 5 | **Retry — last, and carefully** | Experiment 2. 5,322 `UNKNOWN` payments; a naive retry over those is a double charge. Needs provider-side idempotency, which the simulator already honours by `reference`. |

And one item that belongs to phase 8 rather than phase 3, found by the soak:
**crash recovery for payments stranded in `AUTHORIZING` or `INITIATED`.** The
status poller already planned for phase 8 resolves `UNKNOWN`; it does not look
at payments that died before reaching it.

Note the ordering. The instinct is to reach for the circuit breaker and the
retry, and they are fourth and fifth. The first two problems are admission
control and a health check sharing a connection pool, and neither is a
Resilience4j annotation.

## Numbers to beat

Phase 3's after-graph should show, at 200 rps with the same faults:

| Metric | Baseline | Target |
|---|---|---|
| Success rate, control | 100% (p95 13.2 s) | 100% (p95 < 1 s) |
| Success rate, MySQL +500 ms | **0%** | > 0%, and a *fast* failure rather than a 60 s one |
| `payments-edge` heap exhaustion | **5 occurrences across 6 runs** | zero |
| Hikari pending, peak | 2,896 | bounded, by construction |
| `/actuator/health` under DB latency | 10 s | < 100 ms |
| Worst single request, 30 min soak | **13 m 1 s** | bounded by the deadline budget |
| Payments stranded in a non-terminal state | 124 | zero, or recoverable |

## Reproducing

```bash
./gradlew build bootJar && docker compose up -d --build

MAX_RATE=200 STAGE_DURATION=30s tools/loadtest/run-experiment.sh 00-control ramp.js
CHAOS_LATENCY_MS=3000 MAX_RATE=200 tools/loadtest/run-experiment.sh 01-downstream-latency ramp.js
CHAOS_ERROR_RATE=0.4  MAX_RATE=200 tools/loadtest/run-experiment.sh 02-downstream-errors ramp.js
TOXIC="latency mysql 500" MAX_RATE=200 tools/loadtest/run-experiment.sh 03-mysql-latency ramp.js
BASE_RATE=20 SPIKE_RATE=200      tools/loadtest/run-experiment.sh 04-spike spike.js
RATE=20 DURATION=30m             tools/loadtest/run-experiment.sh 05-soak soak.js
```

Restart the services between runs. A heap left full by the previous experiment
becomes the next experiment's finding.

`run-experiment.sh` resets every chaos layer before and after each run, so a
toxic left over from a previous experiment cannot contaminate the next one
silently.
