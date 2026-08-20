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

| Piece | Solves | |
|---|---|---|
| **Request-body fingerprint** | Key reuse with a *different* payload — was replayed as if identical, which is wrong | done, 7a |
| **Bounded wait, then replay** | Two concurrent requests with the same key; the second waits for the first and replays its answer | done, 7b |
| **In-flight marker in Redis** | Keeps a hundred waiters from polling the row the winner is writing to. An optimisation, not a correctness fix | |
| **Cached response replay** | Returns the stored bytes | done, phase 1 |
| **TTL expiry** | Keys cannot accumulate forever | |

Redis is configured `noeviction` (phase 0) precisely so in-flight markers cannot
be silently evicted under memory pressure.

The fingerprint mismatch case should be a **422**, not a replay — same key,
different body is a client bug and hiding it is worse than surfacing it.

#### 7a, as built

**The fingerprint is an HMAC, not a SHA-256, and that is the finding.** The
material has to include the card number — a key reused across two different
cards is two different charges — and a bare digest of a PAN is not a one-way
function in any sense that matters here. A PAN is at most 19 digits, and
`idempotency_record` sits in the same database as a `payment` table storing the
BIN and the last four in plain text, by design, since phase 1. That leaves about
six unknown digits, one of them a Luhn check digit: under a million candidates,
one hash each, well under a second of laptop time to recover the card number
from its own "hash". Keying it removes the offline attack rather than slowing it
down, because without the secret an attacker cannot compute a candidate at all.
`RequestFingerprint` refuses to construct with a blank secret, so a deployment
that forgets it fails to start rather than silently running without the control.

**Semantic material, not raw bytes — the opposite of `ReplayableResponse`, and
for the opposite reason.** A response is output and byte-identical replay is the
promise. A request is input, and two byte-different requests can be the same
request: a client that upgrades its JSON library and emits fields in a new order
has not changed what it is asking for. Fingerprinting raw bytes would answer 422
to that retry, which leaves the merchant holding a key they cannot use and unable
to determine whether the payment exists — a worse outcome than the bug being
fixed. So the material is a fixed list of named fields, **length-prefixed**: the
first version used a separator byte and could not distinguish `("4200", null)`
from `("4200", "")`, which its own test caught.

**The CVV is excluded.** It never leaves `EdgeApi.Card` — not stored, not hashed,
not forwarded — and including it would make the stored fingerprint a derivative
of it. The cost is that a retry differing only in CVV replays instead of 422ing,
which is correct: same card, same amount, not a different payment.

**The mismatch is checked before the in-flight state.** A reused key is wrong
whether or not the first request has finished, and "try again shortly" invites
exactly the retry that will fail the same way.

**Legacy rows replay rather than 422.** Records written before 7a have no
fingerprint and cannot be backfilled — the request bodies were never stored,
because they contain PANs. `NULL` means "cannot be compared", and the guard
replays with a WARN. It leaves yesterday's hole open for as long as those rows
live, which beats a deploy that turns healthy in-flight retries into errors.

**The old replay test was asserting the bug.** `replayingAKeyReturnsAByteIdenticalBody`
sent a different amount *and* a different card under the same key and required
the first response back. It now sends the same request twice, and the other case
has its own test expecting a 422.

#### 7b, as built

**A 409 cannot satisfy the exit criterion, and is not much use to a caller
either.** One hundred threads sharing a key are supposed to produce one payment
and *ninety-nine replayed responses*; before this they produced one payment and
ninety-nine errors. A 409 tells the caller nothing they can act on — the payment
may or may not be about to exist — and every one of those callers retries into
the request that has not finished yet. So a duplicate now waits for the winner
and replays its answer.

**The wait budget comes from the deadline, not from a constant.** Phase 3a put a
budget on every inbound request, and a fixed wait would be the one unbounded
thing left in a system built around not having any: a duplicate holding on for
250 ms while its caller had 40 ms left writes its reply to a connection nobody is
reading. A reserve is held back from the remaining budget so a waiter that
succeeds at the last possible moment still has time to serialize and write the
response — otherwise the slowest successful waits time out having done all the
work.

`WaitBudget` is an interface rather than a number because
`Deadlines` lives in `resilience-starter`, and an idempotency library that could
not be used without the resilience one would be two libraries pretending to be
separable. The service that has both wires them together; the standalone default
is a short fixed fallback, which is worse and says so.

**Below a minimum, it declines without waiting** — the same reasoning as the
deadline executor's minimum slice. A request with 5 ms left would pay the latency
of one poll and still get the 409, so it keeps what little it had.

**Polling, not notification.** A condition variable or a Redis pub/sub channel is
correct within one process and wrong across several: the waiters for a key are
spread over however many instances are running, and the winner has no idea who
they are. Polling a shared store is the only mechanism that works for the
duplicate that landed on another node, which is the normal case. The interval
doubles to a ceiling, because a hundred waiters at a flat 10 ms would put ten
thousand queries a second on the row the winner is trying to write — the waiters
slowing down the request they are waiting for. That is also the argument for the
Redis marker next: not correctness, which the durable store already has, but load
applied at the worst possible moment.

**The wait introduces a race, and the fingerprint is re-checked on every poll.**
A claim can be released and re-taken while a duplicate waits on it — the winner
fails, releases, and an unrelated request takes the same key with a different
body. Replaying that response would hand the waiter an answer to somebody else's
question.

**A 409 now means something.** It is one of three things rather than a shrug: the
first request is slower than this one's remaining budget, or this request arrived
with almost none of its budget left, or the winner failed and released its claim
while this one waited.

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

- [~] 100 concurrent threads, same idempotency key → **exactly one** payment
      created, 99 replayed responses, zero duplicates — 7b. Holds at the guard
      with 100 genuinely concurrent virtual threads: 1 run, 100 identical
      responses. At the HTTP boundary it is asserted at 16 threads rather than
      100, because every waiter polls through its own `REQUIRES_NEW`
      transaction and a hundred of those against a test-sized H2 pool would be
      measuring pool starvation — which is real, is the headline of this
      phase's later half, and is not what that test is about. **Not yet
      measured on the live stack under k6**, which is what the criterion
      actually asks for
- [x] Same key, different body → **422**, and the work does not run — 7a.
      Unit-tested at the guard, the fingerprint and the HTTP boundary
- [ ] Deadlock reproduced on command, then eliminated — **both documented**
- [ ] Pool-starvation graph showing that unbounded concurrency just relocates
      the bottleneck
- [ ] Pumba SIGTERM mid-payment → in-flight requests complete or land in
      `UNKNOWN`; nothing lost
- [ ] Virtual vs platform benchmark written up with numbers

## Traps

**Hashing a PAN and calling it protected.** Found in 7a. The BIN and last four
are stored in plain text beside it, which reduces a 16-digit card to about a
million candidates — a bare SHA-256 hands the number back in under a second. Key
the hash, or do not include the PAN.

**A separator byte in a fingerprint over caller-supplied text.** `("42", "00")`
and `("4200", "")` produce the same material, so a fingerprint built that way
cannot detect the exact class of change it exists for. Length-prefix instead.

**Testing idempotency sequentially.** 100 requests one after another all hit the
replay path and prove nothing. They must be genuinely concurrent — that is what
exercises the in-flight marker and the unique constraint race. Met in 7b by
holding every thread on a `CountDownLatch` and making the winner's work slow
enough that the other 99 are certainly inside the wait rather than arriving after
it finished.

**A fixed wait, in a system that already knows how long it has.** Found while
building 7b. A duplicate that waits 250 ms for a caller with 40 ms of budget left
is writing to a connection nobody is reading — and it is the only unbounded thing
left after phase 3a. Take the wait from the deadline, and keep a reserve back for
writing the response.

**Waiters that slow down the winner.** A hundred duplicates polling a shared row
every 10 ms is ten thousand queries a second aimed at the row the winner is
trying to write to. Back off, or the wait makes the thing it is waiting for
slower.

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
