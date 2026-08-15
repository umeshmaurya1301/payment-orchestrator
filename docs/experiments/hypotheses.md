# Hypotheses — written before the baseline runs

Recorded here, before any of the three experiments were executed, and left
unedited afterwards. `00-baseline.md` reports what actually happened and quotes
these predictions verbatim.

The point of writing them down is not to be right. It is that memory
reconstructs a prediction to match the outcome, silently and convincingly, and a
file written first is the only defence against that.

---

## System under test

Phase 1 as delivered. **No resilience of any kind**: no timeouts (not even a
connect timeout), no retries, no circuit breakers, no bulkheads, no fallbacks.
Hikari pools at 20 connections. Virtual threads enabled on every service.

Call chain for one payment:

```
k6 → payments-edge → payment-orchestrator → psp-connector → mock-psp-simulator
        (MySQL)          (MySQL)               (MySQL vault)
```

---

## Experiment 1 — simulator at 3000 ms, ramp to 500 rps

**Prediction.** The edge is the first thing to look broken and the last thing to
actually be broken. With virtual threads there is no fixed servlet pool to
exhaust, so I expect no "thread pool full" symptom anywhere — instead the queue
should form at the first *bounded* resource in the chain, which is a Hikari
pool.

I expect the order to be:

1. `psp-connector` accumulates in-flight requests, because it is the one
   waiting on the 3-second downstream. Virtual threads make this cheap, so I
   expect it to absorb far more concurrency than a platform-thread service
   would.
2. `payment-orchestrator`'s Hikari pool saturates — `hikaricp_connections_pending`
   goes positive — because each payment holds a connection for its database
   writes while the connector call is outstanding.
3. Throughput flattens well below 500 rps and end-to-end latency climbs past 3s.

**Rough numbers.** At 500 rps × 3 s, roughly 1500 requests in flight. I expect
the knee somewhere around 100–200 rps, and p95 to exceed 10 s at the top of the
ramp.

**Least confident about:** whether the orchestrator's connection pool actually
saturates. The transaction boundaries were deliberately drawn so no transaction
is open across the connector call — if that holds, the pool may stay almost
idle and the queue may form somewhere I have not predicted.

---

## Experiment 2 — simulator at 40% errors, ramp to 500 rps

**Prediction.** Error rate stays roughly proportional. There is nothing in the
system that could amplify it: no retries (so one upstream failure is one
downstream failure, not three), and no circuit breaker (so no state that could
turn a partial failure into a total one).

I expect approximately 40% of payments to end in `UNKNOWN` and 60% in
`AUTHORIZED`, and I expect **latency to improve** compared with the healthy
baseline, because a failing call returns faster than a successful one.

**The interesting part is not the ratio, it is the state.** Every one of those
`UNKNOWN` payments is a card that may have been charged. At 500 rps and 40%
errors, that is roughly 200 unresolvable payments per second accumulating with
no poller to resolve them.

**Least confident about:** whether the error rate is exactly proportional or
slightly amplified by connection churn on the failing path.

---

## Experiment 3 — Toxiproxy +500 ms on MySQL, ramp to 500 rps

**Prediction.** This is the one that hurts most, and the amplification factor is
what I most want to measure. A single manual payment with this toxic applied
took **25.7 s** against a ~50 ms baseline while the whole system was otherwise
idle — a ×500 amplification from a ×1 fault, which implies something like 25–50
database round trips per payment across the three services.

So I expect:

1. Collapse at a far lower rate than experiment 1 — single digits of rps.
2. Hikari pools saturated on both `payments-edge` and `payment-orchestrator`,
   with `pending` clearly positive.
3. `/actuator/health` on the edge to be affected too, which is the point the
   phase-2 notes make about unrelated endpoints: the health endpoint touches no
   database, so if it slows down, the cause is contention for something shared
   rather than for the database itself.

**Least confident about:** the health endpoint. It should be unaffected — it
does no database work. If it is affected anyway, that is the most interesting
result in the whole baseline, and I do not currently have an explanation for it.

---

## What would make me wrong in an interesting way

- Any "thread pool exhausted" error at all. Virtual threads should mean the
  constraint moves to a pool of connections rather than a pool of threads. If
  something still runs out of threads, my model of what virtual threads do is
  wrong.
- The orchestrator's Hikari pool staying idle in experiment 1. That would mean
  the transaction boundaries are doing more work than I credited them with.
- Error *amplification* in experiment 2, in a system with no retries. There is
  no mechanism I can name that would cause it, so if it happens I have missed
  something structural.
