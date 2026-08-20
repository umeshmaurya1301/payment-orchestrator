# 18 — Where the queue goes when you remove the thread limit

**Phase 7, section 10.** `tools/loadtest/pool-starvation.sh`

---

## Hypothesis

This project has run virtual threads since phase 0, and the note in
`application.yml` has made the same claim since then:

> Virtual threads do not fix a bounded resource, they move where the queue
> forms. The Hikari pool below is exactly such a bounded resource.

That is an assertion. It stays an assertion until somebody watches the queue
move.

The prediction: put 800 ms in front of MySQL and send steady load. Virtual
threads mean the platform threads never block, so the JVM will happily carry
thousands of in-flight requests. The connection pool is still 20. Therefore

- `hikaricp_connections_active` pins **flat at `maximum-pool-size`** — the ceiling
- `hikaricp_connections_pending` **climbs** — the queue, in its new home
- thread count stays roughly **flat** — they are virtual, and cheap
- throughput goes **flat**, latency **climbs**

**What would falsify it:** `pending` staying at zero while throughput holds. That
would mean the pool is not the constraint and this graph describes something
else. The script asserts on it so that outcome fails rather than passes quietly.

## Setup

Two arms against the live stack, 45 seconds each, 60 concurrent requests per
wave. Toxiproxy already sits in front of MySQL — the orchestrator's datasource
URL points at `toxiproxy:23306` — so the fault needs no code change and no
restart.

```
DURATION=45 CONCURRENCY=60 tools/loadtest/pool-starvation.sh
```

Arm A runs with the proxy clear. Arm B adds `+800ms` to every MySQL round trip.
`clear-all` runs on entry and again from an `EXIT` trap, because a toxic left
behind is the fastest way to produce a graph that means nothing.

## Graph

```
ARM A - HEALTHY DATABASE
  t        active     idle  pending      max   threads   payments
  12s         1.0     19.0      0.0     20.0        75        805
  18s         2.0     18.0      0.0     20.0        75       1140
  24s         2.0     18.0      0.0     20.0        75       1491
  30s         1.0     19.0      0.0     20.0        75       1800
  42s         0.0     20.0      0.0     20.0        75       1860

ARM B - 800ms IN FRONT OF MYSQL
  t        active     idle  pending      max   threads   payments
   6s         6.0     13.0      0.0     20.0        70          0
   9s        20.0      0.0     11.0     20.0        71         16
  12s        20.0      0.0     25.0     20.0        71         31
  15s        20.0      0.0     42.0     20.0        71         39
  24s        20.0      0.0     43.0     20.0        71         50
  33s        20.0      0.0     44.0     20.0        69         59
  42s        20.0      0.0     74.0     20.0        69         60
              ^^^^      ^^^      ^^^^                ^^^
              pinned    zero     the queue           flat
```

## Actual result

| | healthy | +800 ms | change |
|---|---|---|---|
| payments completed in 45s | **1,860** | **113** | **16.5× fewer** |
| peak `hikaricp_connections_pending` | **0** | **74** | the queue appeared |
| peak `hikaricp_connections_active` | 3 / 20 | **20 / 20** | pinned at the ceiling |
| `jvm_threads_live_threads` | 75 | 69–71 | **unchanged** |

```
   ok   the queue moved to the connection pool         74
   ok   throughput fell while threads were free        113 < 1860
```

Idle connections go to **zero** and stay there. Active pins at exactly 20 and
never moves again. Everything arriving after that waits, and `pending` is the
count of what is waiting — 74 requests holding nothing but a place in line.

The bottleneck did not disappear when the thread limit did. It relocated, from a
queue nobody could see to a queue with a gauge on it.

## What surprised me

**The thread count is the metric that shows nothing, and it is the one everybody
looks at.** `jvm_threads_live_threads` reads 75 in the healthy arm and 69–71
under starvation — it goes *slightly down*. A reader watching thread count would
conclude the system was less busy at the moment it stopped being able to do work.

That is not a flaw in the gauge; it is the point of virtual threads. The JVM
counts **platform** threads, and virtual threads are not platform threads. Under
starvation the carriers are parked waiting on the pool, so there are marginally
fewer of them. Every one of the requests piling up is real, in flight, and
invisible to the instrument.

So the pile-up is only observable in `hikaricp_connections_pending`. Before this
phase there was no reason to graph that gauge, and on a platform-thread system
you would not have needed to — the thread pool's own queue would have shown it.
Moving to virtual threads **moved the evidence** as well as the queue, and a team
that migrated without moving its dashboards would see a system that got quieter
and slower at the same time with nothing to explain it.

**16.5× is a bigger collapse than 800 ms explains.** A payment does a handful of
round trips; 800 ms each should cost seconds per payment, not 94% of throughput.
The extra comes from the pool: with 20 connections and ~1 s held per acquisition,
the ceiling is roughly 20 payments per second no matter how many requests arrive,
and everything above that is queueing that makes each *subsequent* request slower
still. The latency is additive; the throughput loss is multiplicative.

**Arm A finished early and that is a result too.** Payments stop climbing at
1,860 by t=33s and the pool goes fully idle — the load generator, not the system,
was the limit in the healthy arm. Worth stating plainly rather than presenting
1,860 as a capacity number: it is a floor on what the system can do, not a
measurement of what it can do.

## The trap this graph sets for its own reader

The phase's trap list names it, and it is worth repeating next to the numbers:
**do not conclude "virtual threads are slow" from this.** Nothing here measures
virtual threads. It measures a 20-connection pool behind an 800 ms database.
Platform threads would hit the same ceiling sooner and with far more memory, and
would have produced the same throughput collapse with a longer thread-pool queue
in front of it.

The correct conclusion is narrower and more useful: **the constraint is the
bounded resource, and the only interesting question is which one it is.** Raising
the pool moves it to MySQL. Raising MySQL moves it to the provider. The queue is
conserved; only its location is configurable.

## Standing questions

- **The pool was never re-sized.** The obvious follow-up is arm C: the same 800 ms
  with `maximum-pool-size` at 50, to show the ceiling moving rather than
  disappearing. That is the version that would make the "conserved, not
  removed" claim from measurement rather than from argument.
- **No latency percentiles here.** The graph shows throughput and queue depth;
  the phase criterion asks for the starvation shape and this is it, but the p99
  climb is the number a reader would want beside it.
- **`hikaricp_connections_pending` has no alert.** It is the single clearest
  leading indicator of this failure and nothing pages on it — the same gap
  experiment 14 closed for consumer lag.
