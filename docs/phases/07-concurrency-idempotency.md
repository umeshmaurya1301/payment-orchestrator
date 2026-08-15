# Phase 7 — Concurrency and idempotency hardening

| | |
|---|---|
| **Estimate** | 2-3 weeks |
| **Depends on** | phases 1, 2 and 6 |
| **Delivers** | the pool-starvation graph, a deadlock reproduced on command, and full idempotency |

## Goal

Survive concurrency, and be able to demonstrate exactly how.

## Why here

**This is the phase SDE-2 loops probe hardest.** It comes late because it needs
`chaos-core`'s sleep-in-held-lock seam (phase 2), the ledger's pessimistic
locking (phase 6), and a baseline showing what unbounded concurrency does
(phase 2).

The headline deliverable is a graph proving something most engineers only
believe abstractly: **virtual threads do not fix a bounded resource, they move
where the queue forms.**

## Prerequisites

- Phase 1's basic idempotency (unique constraint + replay)
- `idempotency-starter` empty shell
- `chaos-core` sleep-in-held-lock seam
- Toxiproxy in front of MySQL
- Virtual threads enabled since phase 0 — so the measurements are consistent

## Implementation

### 1. Full idempotency

Building on phase 1's constraint and replay:

| Piece | Solves |
|---|---|
| **Request-body fingerprint** | Key reuse with a *different* payload — currently replayed as if identical, which is wrong |
| **In-flight marker in Redis, returns 409** | Two concurrent requests with the same key; the second must not proceed |
| **Cached response replay** | Returns the stored bytes |
| **TTL expiry** | Keys cannot accumulate forever |

Redis is configured `noeviction` (phase 0) precisely so in-flight markers cannot
be silently evicted under memory pressure.

The fingerprint mismatch case should be a **422**, not a replay — same key,
different body is a client bug and hiding it is worse than surfacing it.

### 2. Optimistic locking

`@Version` on the payment row. Suits payment state transitions: conflicts are
rare, and a retry on conflict is cheap and correct.

### 3. Pessimistic locking

`SELECT … FOR UPDATE` on ledger balances. Suits balances: conflicts are common,
and optimistic retry under contention degenerates into a livelock.

Being able to say *why each one is used where* is the point.

### 4. Deliberately reproduce a deadlock

Use `chaos-core`'s sleep-in-held-lock with **two concurrent refunds** acquiring
two balance rows in opposite orders.

Then fix it via **consistent lock ordering** (sort by account ID before locking).

**Keep the logs of both states.** `SHOW ENGINE INNODB STATUS` output showing the
deadlock, and the same test passing afterwards. A deadlock you can reproduce on
command is far more convincing than one you once saw.

### 5. Virtual threads vs platform pool benchmark

On the connector fan-out path. **Real numbers, not assertions.** Measure
throughput, P99, and memory at several concurrency levels.

### 6. `StructuredTaskScope`

Parallel fan-out for status checks across three providers. Both shapes:

- **take first success** (`ShutdownOnSuccess`) — for "any provider can answer"
- **all-or-nothing** (`ShutdownOnFailure`) — for "need every result"

Note the API is still evolving across JDK versions; pin what you use.

### 7. Lock-free primitives

CAS state transitions via `AtomicReference`; `LongAdder` for hot counters.

`LongAdder` over `AtomicLong` under contention: it spreads updates across cells
and only sums on read, so writers stop fighting over one cache line.

### 8. Redis distributed lock for the recon job

And **be ready to explain why Redlock is contested.** Short version: it assumes
bounded clock drift and bounded GC pauses. Under a long stop-the-world pause a
node can believe it still holds a lock that has expired. For a recon job the
correct framing is that the lock is an *optimisation to avoid duplicate work*,
not a *correctness guarantee* — correctness comes from the job being idempotent.

### 9. Graceful shutdown / in-flight drain

Pumba SIGTERMs the orchestrator mid-payment. In-flight requests complete or land
in `UNKNOWN`; **nothing is lost.**

`server.shutdown: graceful` has been set since phase 0, and the Dockerfile's
`exec` ensures SIGTERM actually reaches the JVM rather than being swallowed by a
shell.

### 10. Hikari pool starvation demo

Toxiproxy adds 800 ms to MySQL under load. Watch virtual threads pile up waiting
for a bounded pool.

Capture: virtual thread count climbing, Hikari `pending` climbing, active
connections flat at `maximum-pool-size`, throughput flat, latency climbing.

## Key decisions

| Decision | Defence |
|---|---|
| Optimistic on payments, pessimistic on balances | Conflict probability differs by an order of magnitude |
| Fingerprint mismatch → 422, not replay | Same key + different body is a client bug; hiding it is worse |
| `noeviction` Redis | An evicted in-flight marker silently breaks duplicate suppression |
| Consistent lock ordering | The only deadlock fix that scales; retry-on-deadlock is a band-aid |
| Redis lock as optimisation, not guarantee | Correctness comes from job idempotency |

## Exit criteria

- [ ] 100 concurrent threads, same idempotency key → **exactly one** payment
      created, 99 replayed responses, zero duplicates
- [ ] Deadlock reproduced on command, then eliminated — **both documented**
- [ ] Pool-starvation graph showing that unbounded concurrency just relocates
      the bottleneck
- [ ] Pumba SIGTERM mid-payment → in-flight requests complete or land in
      `UNKNOWN`; nothing lost
- [ ] Virtual vs platform benchmark written up with numbers

## Traps

**Testing idempotency sequentially.** 100 requests one after another all hit the
replay path and prove nothing. They must be genuinely concurrent — that is what
exercises the in-flight marker and the unique constraint race.

**Assuming the unique constraint is enough.** It prevents the duplicate row; it
does not stop two threads both calling the provider before either commits. That
is what the in-flight marker is for.

**Pinning carrier threads.** `synchronized` blocks around blocking I/O pin the
carrier thread and quietly destroy virtual-thread scaling. Use `ReentrantLock`.
This is the most likely reason a virtual-thread benchmark disappoints.

**Deadlock tests that are flaky.** That is what the `chaos-core` sleep seam is
for — make it deterministic, not probabilistic.

**Concluding "virtual threads are slow" from the starvation demo.** The
conclusion is that the *pool* is the bottleneck. That is the whole insight.

## Interview payload

The pool-starvation demo is the strongest artefact in the project:
**virtual threads don't fix a bounded resource, they just move where the queue
forms.** Very few candidates can demonstrate this with a graph.

**Be ready for:** *"So how do you fix it?"* You do not fix it by adding threads.
You either raise the bound (bigger pool, if the DB can take it), reduce hold
time (shorter transactions, `open-in-view: false` — already set), or shed load
at admission (phase 3e's rate limiter). Adding concurrency to a saturated
bounded resource only lengthens the queue.
