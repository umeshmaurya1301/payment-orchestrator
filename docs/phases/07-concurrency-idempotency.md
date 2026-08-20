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
| **TTL expiry** | A key burned by a dead process becomes usable again; records do not accumulate forever | done, 7c |

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

#### 7c, as built

**A dead process burned its key forever.** The claim row is written before the
work and updated after it; a SIGKILL, an OOM or an evicted pod in between leaves
it claimed and unanswered, and nothing ever cleaned it up. `IdempotencyGuard`
releases on a thrown exception, which covers the ordinary failure and cannot
cover the process not being there — which is precisely the case an idempotency
key exists for, because the caller does not know what happened and wants to
retry safely.

**The claim TTL has a floor, and the floor is not a tuning choice.** Taking over
a claim is deciding the first request is dead. Decide that while it is merely
slow and both run: the provider is called twice. So the window must outlast the
longest a legitimate request could still be running — which is *knowable* here
rather than guessable, because phase 3a put a hard ceiling on it
(`DEADLINE_MAX_BUDGET_MS`, 60 s, clamped even for a caller who asks for more).
The default is fifteen minutes, fifteen times the ceiling, because the costs are
asymmetric: too long and a key is unusable for a while after a crash; too short
and somebody is charged twice.

**The takeover is one conditional `UPDATE`, and the row count is the decision.**
Read-then-write would let two requests both see the same expired claim and both
proceed — putting the double charge back at the recovery path after the unique
constraint had removed it from the normal one. Its predicates are the safety
argument: never a completed record, never a row with no claim window (a
pre-7c row has no opinion about when its owner would be dead, and taking a claim
on no opinion is how a live request gets duplicated), and only past the window.
The window resets on takeover, or the second taker inherits an expired one and a
third takes it straight off them.

**Two TTLs, two different questions.** `claim_expires_at` is when an *unanswered*
claim may be taken over. `expires_at` is when a *completed* record stops being
replayable, set at completion rather than at claim — dating retention from when
the work started would shorten the window by however long it took. Twenty-four
hours, after which the key is simply new again: a merchant reusing a key a day
later is not retrying, they are issuing a fresh request.

**The sweeper is scheduled, unlike phase 6j's balance repair, and the contrast
is the point.** A repair that runs by itself hides the bug that made it
necessary. Expiry is not a symptom of anything — a record reaching its retention
window is the design working — so there is nothing for an operator to learn from
pressing a button every day.

**Bounded batches, because the first run is the dangerous one.** An unbounded
`DELETE` holds row locks on the table every payment writes to, and the debut run
has the whole accumulated backlog. A cleanup job whose first execution stalls the
payment path is the kind that gets switched off permanently after one incident.

**`@EnableScheduling` had never been on this service.** Without it the sweeper's
annotation would have been inert — the same shape as `@RetryableTopic` before
`@EnableKafkaRetryTopic` in the ledger — and the only symptom would have been a
table that never stopped growing.

### 2. Optimistic locking

`@Version` on the payment row. Suits payment state transitions: conflicts are
rare, and a retry on conflict is cheap and correct.

#### 7d, as built

**The column had been there since phase 1 and had never done anything
observable.** It was added early on the argument that backfilling it later would
be worse — a good argument — and then nothing ever contended it and nothing ever
caught what it throws. A concurrent update produced an unhandled
`ObjectOptimisticLockingFailureException` and a **500 with a stack trace on the
payment path**: the control working correctly, reported as a bug in the service.
A column that is never contended is indistinguishable from a column that is
ignored.

**"A retry on conflict is cheap and correct" is only half true, and the half
that is wrong is the dangerous one.** It holds when the work is repeatable. It
does not hold for `capture`, which calls a provider and moves real money
*before* it writes anything — an automatic retry there turns a lock conflict,
which is the mechanism working, into the second provider call it exists to
prevent. So the HTTP path answers **409** and lets the caller re-read and decide;
409 rather than 500 because nothing is broken, and rather than 503 because
repeating the request unchanged may well be wrong.

**The same exception is retried in the saga, and that is not an
inconsistency.** `CompensationConsumer` lets it escape into its error handler,
because `reverseCapture` *is* idempotent: re-reading and reversing again is
exactly correct, and the provider recognises a reversal it has already
performed. Same exception, opposite handling, decided by the nature of the work
rather than the type of the failure.

**The finding: the version column guards the row, not the money.** Two
concurrent captures both read `AUTHORIZED`, both pass the state check, and
**both call the provider** — the version is consulted at commit, long after the
funds have moved. What stops that being a double charge is the provider's own
idempotency on `providerRef`, built in 6j. Two independent mechanisms, neither
sufficient: one bounds what the database ends up believing, the other bounds
what the customer is charged. `OptimisticLockingTest` asserts both halves, so
removing either fails there rather than in production.

### 3. Pessimistic locking

`SELECT … FOR UPDATE` on ledger balances. Suits balances: conflicts are common,
and optimistic retry under contention degenerates into a livelock.

Being able to say *why each one is used where* is the point.

**Note what 6j already did here, before this phase asked.** The ledger does not
read a balance and write it back at all — `AccountRepository.applyDelta` is a
single `UPDATE … SET balance = balance + :delta`, which needs no application-level
lock because the row lock the database takes for the update *is* the critical
section. That arrived as the fix for 1,911,000 minor units of drift from a
read-modify-write lost update, and it is strictly better than `FOR UPDATE` for
this case: no round trip between the read and the write for anything to happen
in.

So pessimistic locking earns its place in this phase for the case an atomic
delta cannot express — the deadlock demo below, where two refunds must hold
**two** balance rows at once and the interesting question is the order they take
them in.

### 4. Deliberately reproduce a deadlock

Use `chaos-core`'s sleep-in-held-lock with **two concurrent refunds** acquiring
two balance rows in opposite orders.

Then fix it via **consistent lock ordering** (sort by account ID before locking).

**Keep the logs of both states.** `SHOW ENGINE INNODB STATUS` output showing the
deadlock, and the same test passing afterwards. A deadlock you can reproduce on
command is far more convincing than one you once saw.

#### 7e, as built

**`LedgerTransfer`, and why it needed inventing.** Nothing in the ledger held two
locks, because nothing needed to: `applyDelta` is the whole posting path and
takes no application lock at all. A transfer between two accounts is the smallest
honest thing that does — "move this amount only if the source has it" is a read,
a decision in Java, and a write, which is three steps and two gaps that no atomic
delta can close. That is the case pessimistic locking is actually for, and
holding two of its locks is the entire ingredient list for a deadlock.

**The seam is what makes it a test rather than an anecdote.** The window between
taking one row lock and asking for the second is normally microseconds wide,
which is exactly why lock-ordering bugs survive every suite and then happen in
production at 3am. `chaos-core`'s `PAUSE` sits in that gap and widens it to half
a second, so both transactions are guaranteed to be holding one lock and wanting
the other at the same moment. `transfer(A,B)` and `transfer(B,A)` then deadlock
**every time**.

**The fix is ordering on the primary key, not retry.** Sort the two accounts by
`id` before locking: if every transaction takes the lowest-numbered lock first no
cycle can form, because whoever holds it always makes progress. Retry-on-deadlock
also "works" and gets slower exactly as contention rises, which is when it is
needed most. The order is on `id` rather than `accountRef` because the id is
immutable and is what the row lock is actually taken against — ordering on a
derived value is an ordering that can disagree with itself across two snapshots.

**Both arms stay reachable.** `payorch.ledger.transfer.lock-ordering=declared`
keeps the deadlocking version runnable, the way `EVENTS_PUBLISHER=direct` keeps
phase 6's naive dual-write runnable. The phase asks for both states documented,
which means both states have to exist.

**Nearly measured nothing, twice.** The two `LedgerTransfer` beans in the test
are `@Bean` methods rather than `new LedgerTransfer(...)` — a hand-constructed
instance gets no `@Transactional` proxy, so the transfers would have run with no
transaction, held no row locks past each statement, and the test would have
proved that a deadlock does not occur when nothing locks anything. Phase 6d's bug
again, one layer over.

**And a real one found on the way.** A test asserting that a repeated transfer id
is refused *passed the duplicate through*. `uq_entry_event_account` was declared
only in `V1__ledger.sql`, so every schema generated from the entities — tests, and
any future tool — had no such constraint. **The whole of phase 6e's at-least-once
safety rests on that index, and it was reachable only through Flyway.**
`IdempotencyRecord` had made exactly this declaration on the entity, for exactly
this reason, three phases earlier; `LedgerEntry` had not.

### 5. Virtual threads vs platform pool benchmark

On the connector fan-out path. **Real numbers, not assertions.** Measure
throughput, P99, and memory at several concurrency levels.

### 6. `StructuredTaskScope`

Parallel fan-out for status checks across three providers. Both shapes:

- **take first success** (`ShutdownOnSuccess`) — for "any provider can answer"
- **all-or-nothing** (`ShutdownOnFailure`) — for "need every result"

Note the API is still evolving across JDK versions; pin what you use.

#### 7f, as built

**The API has already changed twice, and the tutorials are wrong.**
`ShutdownOnSuccess` and `ShutdownOnFailure` — the two classes every article about
structured concurrency still shows — **do not exist in JDK 25**. The entry point
is `StructuredTaskScope.open(Joiner…)`, with `anySuccessfulResultOrThrow()` and
`allUntil(…)` in place of the subclasses. Code written from a 2023 blog post does
not compile.

**What `--enable-preview` costs, which is more at deploy time than at compile
time.** A class file compiled with it is stamped minor version 65535, and the JVM
refuses to load it unless the flag is passed at runtime *and* the major version
matches the running JDK **exactly**. So the flag appears in three places —
`psp-connector/build.gradle.kts`, the test JVM args, and `JAVA_OPTS` in
docker-compose — and the Gradle toolchain and `eclipse-temurin:25-jre-alpine`
have to move together. Bumping the base image to 26 produces no compile error and
no warning: it produces a service that will not start, in a container, at deploy
time. Scoped to one module so the other five keep emitting ordinary class files.

**Both shapes, because they answer different questions — and one of them can
give you a double charge.** `firstToClaim` takes the first provider that says yes
and cancels the rest. It must **never** be used to conclude that *nobody* has a
payment: an empty result means "no provider said yes before the deadline", which
is not "no provider holds this". `askEveryone` is the one that can answer that,
because it reports which providers stayed silent — and `nobodyHasIt()` is true
only when every provider was asked and every one of them said no. Silence is not
a no, and re-authorizing on the strength of it would charge someone who has
already been charged.

**A "no" must not win the race.** `anySuccessfulResultOrThrow` races on
*returning*, and the provider that has never seen the reference returns fastest
of all — it does no work. Letting it win would cancel the provider that actually
holds the payment. So not-found is expressed as a failed subtask. The test that
catches this puts the payment on the *slowest* provider.

**All-or-nothing is the wrong joiner here.** One provider being down is the
normal state of a three-provider system — it is what phases 3 and 5 exist to
survive. Cancelling the fan-out on the first failure throws away two good answers
to report one bad one, and a reconciliation that refuses to run whenever any
provider is unhealthy is a reconciliation that never runs.

**The reason it is `StructuredTaskScope` and not an `ExecutorService`:** the
deadline travels with the fork. `Deadlines` is a `ScopedValue`, visible to the
binding thread and to threads forked inside a structured scope — *and to nothing
else*. Hand the same work to a shared executor and `Deadlines.current()` comes
back empty on the far side, so every provider call runs unbounded. Asserted from
inside a forked subtask, because that is the only place the claim can be checked.

**Two bugs of my own, both caught by the tests.** The fan-out fell back to its
50 ms *floor* when no deadline was bound, so every provider timed out and it
reported that nobody held a payment somebody did — a floor and a fallback are
different questions that happen to want a number. And a convenience constructor
overload made Spring fail the whole context with *"No default constructor
found"*, naming neither constructor.

**Not yet wired to an endpoint.** `StatusFanout` is a component with no caller:
its consumer is phase 8's reconciliation, which is what needs to ask "does
anybody have this `UNKNOWN` payment". Said plainly rather than quietly, because
"looks configured, does nothing" is the failure this project keeps finding.

### 7. Lock-free primitives

CAS state transitions via `AtomicReference`; `LongAdder` for hot counters.

`LongAdder` over `AtomicLong` under contention: it spreads updates across cells
and only sums on read, so writers stop fighting over one cache line.

#### 7g, as measured

Both claims this project leans on are repeated in every article about virtual
threads. **One of them stopped being true in JDK 24, and this document was
carrying the obsolete version.** `LockFreePrimitivesTest` runs with the suite so
neither has to be taken on trust again.

**`synchronized` does not pin the carrier thread on JDK 25.** JEP 491 (JDK 24)
removed it. Measured here: 200 virtual threads each blocking 100 ms on **its own**
monitor finish in **106 ms**, against the **~900 ms** pinning would cost across 24
carriers — and `ReentrantLock` does the same work in 107 ms. The advice "always
use `ReentrantLock` with virtual threads" is now folklore.

Getting this measurement wrong is easy, and I did it first: the obvious version
has every thread share one lock, which measures **mutual exclusion**, not
pinning. They are supposed to serialize — 64 threads holding one lock for 50 ms
each taking 3.2 s is the lock working perfectly, and it looks exactly like
catastrophic pinning. Each thread has to lock its own object, so that the only
thing which *could* serialize them is the carrier being held.

**`LongAdder` wins under contention and loses without it**, 2,000,000 increments
per writer on 24 carriers:

| writers | `AtomicLong` | `LongAdder` | ratio |
|---:|---:|---:|---:|
| 1 | 8 ms | 13 ms | **0.62×** — `LongAdder` loses |
| 4 | 67 ms | 13 ms | 5.15× |
| 24 | 507 ms | 20 ms | 25.35× |
| 96 | 2,244 ms | 76 ms | 29.53× |

The single-writer row is the half nobody quotes and the half that decides where
to use it. A striped counter costs more per increment and more per read, so
uncontended it is pure overhead — "replace every `AtomicLong` with a `LongAdder`"
is not the lesson.

**Which is why nothing was converted.** The split in this codebase already
matches the numbers: every per-request counter in `infra-core` — `Retrier`,
`SemaphoreBulkhead`, `RetryBudget`, `ChaosSeams` — is a `LongAdder`, and the
service-level counters that move once per Kafka message or once per scheduled run
are `AtomicLong`. The second group is not an oversight waiting to be tidied;
converting it would make those counters slower. The deliverable for this
sub-phase is the evidence, not a diff.

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
- [x] A key burned by a process that died mid-request becomes usable again,
      and completed records do not accumulate forever — 7c. Thirteen tests,
      most of them about the takeovers that must **not** happen, which is the
      half that fails silently
- [x] A concurrent modification of one payment is detected and answered, not
      swallowed and not 500'd — 7d. Two genuinely concurrent captures held
      inside the provider call by a barrier: one 200, one 409, one `CAPTURED`,
      exactly one `payment.captured` outbox row — and **two** provider calls,
      which is the part worth knowing
- [~] Deadlock reproduced on command, then eliminated — **both documented**.
      7e. `LedgerDeadlockTest` reproduces it deterministically with the
      sleep-in-held-lock seam and shows a consistent lock order removing it,
      with both implementations kept runnable. **The `SHOW ENGINE INNODB
      STATUS` capture the criterion also asks for is missing**: these run
      against H2, which detects the cycle and breaks it but is not InnoDB, and
      the compose stack was unavailable. Not written from memory
- [x] Parallel fan-out across three providers, in both shapes, with the
      request deadline propagating into every forked subtask — 7f. Twelve
      tests, asserting timing and cancellation rather than protocol: a
      fan-out that quietly ran sequentially would pass every functional
      assertion anybody would think to write
- [ ] Pool-starvation graph showing that unbounded concurrency just relocates
      the bottleneck
- [ ] Pumba SIGTERM mid-payment → in-flight requests complete or land in
      `UNKNOWN`; nothing lost
- [x] Lock-free primitives justified by measurement rather than by reflex —
      7g. The `LongAdder` split in this codebase already matched the numbers,
      so nothing was converted; the deliverable is the evidence and one
      corrected trap
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

**Expiring a claim faster than a request can finish.** The most tempting number
to shorten, and the one that reintroduces the double charge. The floor is the
request deadline ceiling, not a guess about how long things usually take.

**Read-then-write in the takeover path.** The unique constraint prevents the
duplicate on the normal path; a takeover implemented as SELECT-then-UPDATE puts
it straight back on the recovery path, where it is harder to see and rarer to
hit.

**A self-invoked `@Transactional` method.** Phase 6d's bug, nearly repeated: the
sweeper's batch method is called from its own scheduled method, so the annotation
would have been silently inert and each batch would have run without a
transaction. It uses a `TransactionTemplate` instead — a transaction from an
object, which cannot quietly not be there.

**Assuming the unique constraint is enough.** It prevents the duplicate row; it
does not stop two threads both calling the provider before either commits. That
is what the in-flight marker is for.

**A `@Version` column nobody has ever contended.** It looks identical to one
that works. Found in 7d, six phases after the column was added: nothing caught
the exception either, so the first real conflict would have been a 500 on the
payment path. Contend it in a test, and handle what it throws.

**"Retry on optimistic-lock conflict" as a reflex.** Correct only when the work
is repeatable. When the work called a provider before it wrote, the retry is a
second provider call — the lock conflict was the mechanism working, and the
retry is what turns it into the double charge.

**Expecting optimistic locking to prevent a double charge.** It bounds what the
database believes, not what the customer is charged: both writers had already
called the provider by the time either was refused. The provider's own
idempotency is the other half, and neither is sufficient alone.

**Reading a `StructuredTaskScope` tutorial written before JDK 24.**
`ShutdownOnSuccess` and `ShutdownOnFailure` are gone. Check the API against the
JDK you are actually on, and pin it — a preview class file is welded to one JDK
version and fails at *deploy* time, not compile time.

**Racing on "who returns first" when one answer is cheap.** A provider that does
not hold the reference answers instantly, so a naive first-success fan-out is won
by the wrong provider every single time and cancels the one that had the answer.

**Concluding "nobody has it" from a first-success fan-out.** That is a timeout
wearing a negative answer's clothes, and acting on it is a double charge.

**Confusing a floor with a fallback.** "How little is it worth starting with" and
"what if nobody said" are different questions. Answering the second with the
first gave the fan-out 50 ms to hear from three providers.

**~~Pinning carrier threads.~~ OBSOLETE ON THIS JDK — corrected in 7g.** This
entry read: *"`synchronized` blocks around blocking I/O pin the carrier thread and
quietly destroy virtual-thread scaling. Use `ReentrantLock`. This is the most
likely reason a virtual-thread benchmark disappoints."* That was correct for JDK
21 and is **wrong for JDK 25**: JEP 491 landed in JDK 24 and a virtual thread
blocking inside `synchronized` now releases its carrier like any other blocking
operation. Measured in `LockFreePrimitivesTest` — 106 ms where pinning would cost
~900 ms.

Left in place rather than deleted, because the more useful trap is the
meta one: **a trap list is a cache, and this entry was stale for two JDK
releases.** Every piece of advice here has a version it was true for.

**Measuring mutual exclusion and calling it pinning.** The natural pinning test —
many virtual threads, one shared lock — measures the lock doing its job. They are
*supposed* to serialize. Give each thread its own lock, or the result is
indistinguishable from the bug being looked for.

**Deadlock tests that are flaky.** That is what the `chaos-core` sleep seam is
for — make it deterministic, not probabilistic. Confirmed in 7e: with the seam
armed at 500 ms the deadlock is certain, and without it the same two transfers
almost never collide.

**A constraint that exists only in the migration.** Found in 7e. Every schema
generated from the entities — every test, every tool — silently lacks it, and a
test written to prove idempotency will instead prove that duplicates are
accepted. Declare it on the entity *and* in the migration; they are two different
schemas with two different authors.

**Constructing a `@Transactional` bean with `new` in a test.** No proxy, no
transaction, no locks held past the statement — and a deadlock test that passes
because nothing was locking anything.

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
