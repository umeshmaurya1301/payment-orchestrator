# 10 — The outbox, both relays, and killing brokers (phase 6a–6d)

*"How do you write to the database and publish to Kafka atomically?"* — asked in
almost every distributed-systems interview, and usually answered from memory.
This measures it instead, then builds both answers and compares them.

> **Headline.** With a naive dual write, a 30-second broker outage cost **20
> payments out of 60** their events — permanently. Every one returned `201`,
> every one is `AUTHORIZED` in MySQL, and no event for any of them exists. The
> brokers came back and the gap did not close.
>
> With the outbox: **60 payments, 60 events, gap 0.**
>
> Getting there introduced two bugs worse than the one being fixed. The relay's
> claim query **blocked the payment path** and stranded four payments in
> `AUTHORIZING` with `Lock wait timeout exceeded` — a card possibly charged and
> the payment recorded as neither authorized nor failed. And Spring's
> `@Transactional` was **silently inert** on the relay's own methods, which would
> have republished every event on every poll forever while looking healthy.
>
> CDC then beat polling on every number that could be measured — **p50 5 ms vs
> 260 ms**, and no idle database load at all — and is still not the default.

---

## Hypothesis

Written before the runs.

> The dual-write gap is real but I expect it to be small and hard to trigger — a
> narrow window between commit and publish, needing a well-timed crash.
>
> The outbox should be mechanically dull: a table, an insert, a poller.
>
> **Least confident about:** whether the polling relay's cost is noticeable at
> this scale. I expect CDC to win on latency and to lose on operational weight,
> and I expect the numbers to be close enough that the choice stays a judgement
> call.

---

## A. The gap is not narrow, and does not need a crash

`tools/loadtest/dualwrite-gap.sh`, three windows of 20 payments:

| window | payments | events |
|---|---|---|
| Kafka healthy | 20 | 20 |
| **Kafka down** | **20** | **0** |
| Kafka healthy | 20 | 20 |
| **total** | **60** | **40** |

**Permanent gap: 20.** No crash was needed and no timing was involved. The
brokers were simply unreachable for thirty seconds, and every event owed during
that window was lost the moment the call stack unwound — because the only record
that an event was owed *was* the call stack.

What makes this dangerous is the shape of the failure:

```
payment state:   AUTHORIZED  (all 60)
HTTP response:   201         (all 60)
log level:       WARN        (20 lines, one per loss)
```

The database is correct. The merchant is happy. The card is charged. Nothing
looks like an outage, and the only signal is twenty WARN lines that somebody
would have to notice and correlate. That is why this failure mode survives in
production systems for years.

The hypothesis was wrong about the window being narrow. **Every event owed
during an outage is lost, not just the ones caught mid-flight**, because a
retry that lives in a request thread dies with the request.

---

## B. The outbox, and where the row is written

The fix is to stop having two writes. The event becomes a row in the *same*
transaction as the payment, so "the payment is authorized" and "an event is
owed" are one atomic fact.

**Where that insert happens is the entire design, and it is easy to get wrong in
a way that reviews cleanly.** Phase 6a emits its event from `PaymentService`,
after the `@Transactional` persistence method has returned *and committed*.
Reusing that call site for the outbox would have produced code that looks like a
textbook outbox, passes review, and loses events at exactly the same rate.

So the write lives in `PaymentPersistence`, beside the state change, and
`OutboxWriter` throws if it ever finds itself outside a transaction:

> an outbox write attempted outside a transaction — this is a dual write wearing
> an outbox's clothes, and it loses events exactly as fast.

Result, same experiment: **60 payments, 60 events, gap 0.**

---

## C. Two bugs worse than the one being fixed

### 1. The relay blocked the payment path

The first relay claimed rows with `SELECT ... FOR UPDATE` inside a
`@Transactional` method and published to Kafka while holding the locks. Measured:

```
4 payments stranded in AUTHORIZING
CannotAcquireLockException: Lock wait timeout exceeded
  [insert into outbox_event ...]
```

The claim's range scan takes next-key locks on the `published_at IS NULL` range
— which is precisely the gap every *new* outbox row is inserted into. So a
payment committing its terminal state waited fifty seconds behind the relay and
then rolled back entirely.

**That is worse than the event loss it replaced.** A lost event leaves a correct
payment and a short ledger; a rolled-back terminal transition leaves a card that
may be charged against a payment recorded as neither authorized nor failed.

It is also phase 2's rule reappearing — *no transaction may be open across a
remote call* — which `PaymentPersistence`'s own javadoc states. The relay had a
paragraph explaining why it was exempt: a background thread, its own connection,
a bounded batch. The measurement disagreed. **A lock is a lock regardless of
which thread holds it.**

Claim, publish and mark are now three separate short transactions, with a
**lease** instead of a held lock (`V10`): claiming stamps `claimed_at` and
commits, releasing every lock before anything touches the network. The lease also
buys multi-instance safety and crash recovery — a relay that dies mid-publish has
its rows retried once the lease expires, rather than stranding them.

### 2. `@Transactional` was silently inert

`claim()` and `markPublished()` were called by `relay()` on the same object.
Spring's `@Transactional` is proxy-based, so a self-invocation never touches the
proxy and every annotation on those methods was doing nothing.

The consequence would have been worse than a missing lock: `markPublished`
mutates a managed entity and relies on the transaction committing to flush it.
With no transaction there is nothing to flush, so the row would never be marked,
the next poll would claim it again, and **the relay would republish every event
forever while reporting perfect health.** At-least-once would have quietly become
at-least-once-per-poll.

Moved into `OutboxStore`, so every call crosses a bean boundary and the proxy is
always in the path.

### 3. And blocking is not failing

With `max.block.ms` at its 60-second default, `send()` blocks waiting for
metadata *before* returning a future — and the relay is a single scheduled
thread. A 30-second outage produced a **247-second drain with zero errors
logged**. Nothing failed; everything waited. Now 5 seconds, and a failed publish
releases its lease so the retry is immediate rather than a minute later.

---

## D. Polling vs CDC, on one population of payments

Both relays read the same `outbox_event` table and publish to different topics,
so this is one set of payments seen two ways rather than two runs at two
different times.

| | polling relay | Debezium CDC |
|---|---|---|
| publish lag p50 | 260 ms | **5 ms** |
| publish lag p95 | 559 ms | **13 ms** |
| idle DB load | ~2 SELECT/s | **0** |
| new infrastructure | none | a container, a connector, a schema-history topic, a MySQL account |

The polling numbers are exactly what theory predicts, which is a good sign the
instrument is sound: with a 500 ms interval, the average wait is half an interval
(260 ms) and the tail is one full interval (559 ms). Lowering the interval trades
latency against that idle query rate, one for one.

CDC wins every measurable comparison and **is still not the default.** What it
costs does not appear in the table above:

- **A MySQL account with global `REPLICATION SLAVE` privileges.** These cannot be
  scoped to a database, let alone a table — so the CDC user can read the binlog
  for the whole server, including the token vault's writes. This project
  otherwise gives every account exactly one job (`vault_writer`, `vault_reader`,
  `config_reader`). CDC is the first thing that could not be scoped, and on a
  shared database that is a conversation with a security team.
- **A component that reports RUNNING while publishing nothing.** Which is exactly
  what it did on first configuration: `route.by.field` defaults to Debezium's own
  `aggregatetype` column convention, and against a table that does not use their
  names every record dies in the transform while the connector's status stays
  green. Same shape as the phase-4 OTLP property rename — a healthy-looking
  pipeline delivering nothing.
- **Coupling to the table's physical schema**, which is now a published contract
  rather than an implementation detail.

The honest summary: **CDC is the better mechanism and the polling relay is the
better default**, until the latency actually matters. 260 ms on an event that a
ledger consumes asynchronously is not a number anybody is waiting on.

---

## E. Killing brokers under load (6d)

`tools/loadtest/broker-kill.sh`. `docker kill`, not `stop` — SIGKILL, no
graceful shutdown, no chance to hand off leadership.

| window | payments | events | under-replicated |
|---|---|---|---|
| three brokers | 15 | 15 | 0 |
| **one killed** | 15 | 15 | 6 |
| **two killed** | 15 | *held in outbox* | — |
| recovered | — | all drained | 0 |

**45 payments, 45 events, zero lost.**

### The third window is the one that proves anything

Killing one broker is the test everybody runs, and a cluster with
`min.insync.replicas=1` passes it identically — then loses data silently when the
last replica dies. So this kills a **second** broker, taking the cluster below
its minimum, and the producer's own log says what happened:

```
Got error produce response ... payment.events-4, retrying (0 attempts left).
Error: NOT_ENOUGH_REPLICAS
```

Kafka **refused to acknowledge a write it could only place on one replica.**
That refusal is the feature. The 15 events stayed in MySQL, and drained when the
cluster came back.

### What did *not* happen is the interesting part

The relay never gave up — `attempts` stayed at 0 for every row. The producer
retries inside its delivery timeout, and the outbox row is not marked published
until Kafka says yes, so a refusal is simply a slower success. Maximum observed
lag through the whole exercise: **15 seconds**.

And the trap the phase names explicitly held: `__consumer_offsets` stayed RF=3
with full ISR throughout. A "three-broker cluster" whose internal topics are RF=1
passes the data test and loses every consumer's position on the same kill.

---

## What surprised me

**That the outbox's own machinery was more dangerous than the bug it fixed.**
Both of the serious defects were in the relay, not in the concept, and both
would have passed a code review — one was a lock held a few lines too long, the
other was a method call that looked identical to a working one. The dual-write
gap took twenty minutes to measure; making the fix not-worse took the rest of the
phase.

**That "RUNNING" and "healthy" mean so little.** Three separate components
reported success while doing nothing this phase: the Debezium connector with a
failing transform, the producer blocking rather than failing, and the would-be
inert `@Transactional`. In every case the only way to tell was to count what came
out the other end.

**That the measurement script was wrong twice, in opposite directions.** It first
reported zero events while ten sat in the topic (an offsets tool that prints
nothing and exits 0 on Kafka 4), and later reported a permanent gap of 19 against
an outbox that was draining as it printed. "Permanent" is a claim about the end
state, so it now waits for the relay to finish. The direct arm needs no such
wait, which is precisely its problem.

---

## Standing questions

- **Nothing consumes these events yet.** `ledger-notifier` is still the phase-0
  skeleton. The retry-tier topics and DLQ exist and are empty.
- **Trace context does not cross Kafka yet.** A webhook delivered forty seconds
  later will start its own trace until the headers carry `traceparent`.
- **Outbox rows are never deleted.** The table grows forever. A retention job is
  needed, and it must not emit tombstones for a compacted topic — a tombstone for
  a payment event reads downstream as "this payment never happened".
