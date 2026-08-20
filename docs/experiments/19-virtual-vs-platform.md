# 19 — Virtual threads against platform threads

**Phase 7.** `tools/loadtest/virtual-vs-platform.sh`, `tools/loadtest/threads.js`

---

## Hypothesis

Experiment 18 showed throughput collapsing 16× under a saturated connection pool
while running virtual threads, and the phase's trap list says plainly what not to
conclude from it: *"virtual threads are slow."* The bottleneck there was the
pool.

So this benchmark exists to answer the obvious next question with a number. The
prediction, written before the run:

1. Given a **thread-bound** workload — slow I/O that holds a request thread and
   almost no connection — platform threads should ceiling at Tomcat's 200 while
   virtual threads track the provider.
2. Therefore virtual should complete substantially more work at 400 concurrent.

Prediction 2 is wrong, and the reason it is wrong is the finding.

## Setup

The load is a 1.5 s provider with the gates widened, 400 concurrent, 45 s per
arm, one restart between them (`VIRTUAL_THREADS` is now an environment flag on
both services on the request path).

**Why the gates come off.** `psp_config` bounds the request path in three places
— `bulkhead_max_concurrent` 20–160, `egress_tps` 50–500, `deadline_slice_ms` as
low as 50 ms — and at 1.5 s of provider latency that last one times out every
call to `mockpsp` and `psp-a` before it can return. All three are smaller than
the thread count. They are widened for the run and restored from a trap, so this
measures **the thread model** and explicitly not this system as it ships.

```
CONCURRENCY=400 PROVIDER_MS=1500 DURATION=45 tools/loadtest/virtual-vs-platform.sh
```

## Graph

```
                          virtual    platform
   payments created (201)    2,141       2,289      6% apart
   throughput (payments/s)      43          46
   jvm_threads_live             80         276      3.5x
                                ^^          ^^^
                                same work, very different cost
```

Three runs, consistent: 2228/2288, 2141/2289, and an earlier shell-driven pair.
Throughput never separates by more than 6%. Thread count separates by 3.5×
every time.

## Actual result

**Throughput is the same either way.** Not "virtual is slightly ahead" — the
difference is inside run-to-run noise, and it changed sign between runs.

**The same work costs 3.5× fewer OS threads.** 80 against 276, reproducibly.

The reason throughput does not move is that **threads are never the constraint in
this system**. Three attempts to build a thread-bound workload each found a
different bound first:

| Attempt | What bound first |
|---|---|
| Shell loop, gates on | `psp-connector`'s bulkhead — 4,516 permitted, **117 rejected** |
| Shell loop, gates off | The load generator: both arms completed **exactly 1200** = 400 × 3 waves |
| k6, gates off | Something at ~68 in flight — 43/s against 1.5 s — well under Tomcat's 200 |

Even with the bulkhead, the egress limiter and the deadline slice all widened,
400 VUs never put more than ~68 requests in flight. The system tops out below
the platform thread ceiling, so the ceiling is never reached and the two models
cannot be told apart by throughput.

## What surprised me

**The benchmark asserted a belief, and failed three times against a system that
was working correctly.** The original pass condition was `virtual > platform`.
That is not a measurement, it is an expectation with an `if` around it, and it
marked three correct runs as failures. What it should assert — and now does — is
that the two are *within noise of each other* and that virtual costs far fewer OS
threads, because that is the claim the evidence actually supports.

This is the same shape as experiment 17's arm B, one level up: there, an arm that
could not fail; here, an arm that could not pass.

**The load generator was the bottleneck for two runs and said nothing.** The
shell version backgrounded 400 curls and waited for all of them before the next
wave — closed-loop, so throughput becomes 400 divided by the *slowest* request in
each wave. Both arms reported exactly **1200** payments, which is 400 × 3 waves:
the generator's own ceiling showing through, identical in both arms because it
had nothing to do with either. A number that is the same in both arms of a
comparison looks like a clean null result and can just as easily mean the
instrument was measuring itself.

**"Not thread-bound" turned out to be a property of the architecture, not of the
test.** Every route to slow I/O in this system is guarded by something smaller
than the thread count — a bulkhead, an egress budget, a deadline. That is phase
3d and 3e working exactly as designed, and it means a thread-bound workload
cannot be constructed through the front door at all. To measure the thread model
I had to take those guards off, which is worth being explicit about: the run
above describes a configuration this system never runs in.

**So the honest case for virtual threads here is memory, not speed.** 3.5× fewer
OS threads for identical throughput is a real and reproducible result, and it is
a different argument from the one people usually make. The throughput argument
needs a system whose bottleneck is threads; this one does not have that, by
construction, and adding threads to it would change nothing — which is exactly
what experiment 18 concluded from the other direction.

## Standing questions

- **What binds at ~68 in flight?** Neither the pool (20 connections, held
  briefly), nor the widened gates, nor threads. The most likely candidate is an
  HTTP client connection pool between edge → orchestrator → connector, and it is
  unmeasured. That is a fourth bounded resource nobody has named, which is
  itself the point of this phase.
- **No p95 in the table.** The script captures it from k6 and the extraction is
  fragile; throughput and thread count were reproducible enough to publish and
  the latency percentile was not.
- **Memory was never measured.** The whole case here rests on thread count as a
  proxy for footprint. `jvm_memory_used_bytes` per arm would make it a direct
  claim instead of an inference.
