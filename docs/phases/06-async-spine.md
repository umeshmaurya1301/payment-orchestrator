# Phase 6 — Async spine

| | |
|---|---|
| **Estimate** | ~3 weeks |
| **Depends on** | phase 5 |
| **Delivers** | durable eventing, and the DB↔Kafka atomicity question answered properly |

## Goal

Durable eventing, and the atomicity question answered properly.

## Why here

*"How do you write to the DB and publish to Kafka atomically?"* is asked in
nearly every distributed-systems round. Outbox + CDC is the answer, and this
phase builds **both variants** so you can compare rather than recite.

It comes after routing because the ledger needs settled payment outcomes to
be interesting, and after observability because trace propagation through Kafka
is only demonstrable once tracing exists.

## Prerequisites

- Phase 5 complete
- `async` compose profile already defined in phase 0 (Kafka ×3 KRaft, Mongo,
  `ledger-notifier`) — **wired but never exercised**, so expect first-run issues
- Check the memory budget before running `async` and `obs` together

## Implementation

### 1. Kafka — 3 brokers in KRaft mode

No ZooKeeper. Kafka 4.x is KRaft-only. Already in `docker-compose.yml` with
distinct `node.id` and a shared `controller.quorum.voters`.

**The settings that matter, already set in phase 0:**

```
KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 3
KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 3
KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 2
```

The defaults are **1**, and they silently undermine everything below: internal
topics would live on a single broker, so killing that broker loses consumer
offsets and transaction state even though your data topics are replicated three
ways. You would pass the "kill a broker" test on data and fail it on offsets.

### 2. Topics

`RF=3`, `min.insync.replicas=2`, idempotent producer, `acks=all`.

Partition key = **`paymentId`** for per-payment ordering. Ordering is per
partition, so this is what makes "events for one payment arrive in order" true.

### 3. Transactional outbox

In the orchestrator. **Build both variants:**

1. **Polling relay** — simple, easy to reason about, adds latency and DB load
2. **Debezium CDC** — reads the binlog, lower latency and no polling load, but
   adds an operational component and a schema-coupling concern

Doing both is the point. It turns an opinion into a comparison with numbers.

### 4. `ledger-notifier`

**Mongo as a single-node replica set** — `--replSet rs0` plus an init container
calling `rs.initiate()`. Already in compose. Standalone `mongod` **cannot do
transactions**, and the ledger write path needs them.

- **Double-entry ledger.** Balances in MySQL, immutable event journal in Mongo.
- **Outbound webhooks** with HMAC signing and timestamp replay protection.
  Built in 6h. `t=<unix>,v1=<hmac>` over `"<t>." + rawBody`, with the receiver
  in `docker/webhook-sink/` written in Python from the header format rather than
  from the signer — two halves of one codebase always agree, including when both
  are wrong. Measured in experiment 13, and the measurement includes the arm that
  matters: **an unmodified replay inside the tolerance window is accepted.**
  Freshness makes a capture perishable; only the receiver deduplicating on the
  event id refuses one, and that is on the merchant's side of the integration.

  **No retry machinery.** A delivery worth retrying throws onto 6f's ladder. One
  retry mechanism for the service, and it is safe only because 6e made the ledger
  write idempotent — the webhook retry works because the *ledger* is idempotent,
  not because the webhook is.

### 5. Tiered retry topics

`5s` → `1m` → `10m` → DLQ, plus an admin replay endpoint.

Non-blocking retry: a failing message must not head-of-line block its partition.

### 6a. Capture

Built in 6j, because the saga needs something to compensate. `POST
/v1/payments/{id}/capture` at the edge, `/internal/v1/capture` on the connector,
`payment.captured` through the outbox, and a second pair of ledger legs.

**No idempotency key on capture**, deliberately, and the asymmetry with payment
creation is the point: creating twice creates a second charge, so it needs a key;
capture names an existing resource and moves it `AUTHORIZED → CAPTURED`, which
the state machine permits exactly once. A second call is a 409 from
`PaymentTransitions` rather than a second capture.

**Capture never opens the vault.** `PspAdapter.capture` takes no
`DetokenizedCard` - it references the provider's own handle - so the
detokenization boundary still has exactly one caller. Enforced by the signature
and pinned by a test.

### 6. Saga

For capture → ledger → notify, with compensating reversal on failure. Built in
6k.

**Build choreography; be able to argue orchestration.** Choreography suits this
shape (few steps, clear events). Orchestration wins when the flow grows branches
and you need one place to see the state. Know which you would pick at 15 steps.

**What is actually being compensated.** Not a failed capture — the capture
*succeeded*, the provider took the money and answered 200. What failed is the
ledger, four topics later, after the retry ladder gave up. The disagreement is
between two services that both did their jobs, and neither can see it: the
orchestrator's books balance, the ledger's books balance, and they describe
different worlds. There was never a moment when one lock could have covered the
provider, the orchestrator's database and the ledger's database — the
compensation is what not having had one costs.

**The DLT handler requests, it does not recover.** It publishes to
`payment.compensation` and the orchestrator decides. No service tells another
what to do, and the orchestrator answers `NOT_CAPTURED` and does nothing whenever
the original problem was fixed first.

**The reversal cancels the AUTHORIZATION, not the capture** — the capture legs do
not exist, that is why there is a compensation. Reversing them instead would
credit `settlement:card-network` for funds it never sent and leave clearing
short, with `SUM(amount_minor)` still exactly zero. Second time in two phases the
balanced-books invariant has been unable to see a real error.

**Two guards, both necessary.** The DLT handler compensates only when
`state == CAPTURED && !ledger.hasEntryFor(eventId)` — a capture can dead-letter
with its legs already posted, when the *webhook* fails, and reversing on that
basis takes back money the ledger says is owed to fix somebody else's 502. And a
`reversed_capture` tombstone stops a later DLQ replay of the compensated capture
from posting after the fact; nothing else in the ledger would, because that event
genuinely has never been posted.

**Blocking retry on `payment.compensation`, deliberately** — the opposite of the
ledger's ladder, and not an inconsistency. That topic carries at most one record
per dead-lettered capture, so there is no line behind a stuck record to block,
while the two things the ladder gives up (ordering, and four topics of machinery)
matter more here: a compensation is a state transition, and reordering one
against its sibling is not harmless.

### 7. Trace and correlation-ID propagation through Kafka headers

So a webhook delivered 40 seconds later joins the original trace, **and** its log
lines carry the same `traceId` as the originating API call.

`LogFields` already reserves `CORRELATION_ID`, `TRACE_ID` and `SPAN_ID`.

### 8. PII in event payloads

Kafka messages carry **tokens only**.

**DLQ messages are the real risk.** They persist longest and get inspected
manually, which is exactly the combination that leaks. Mask at **produce** time,
not at read time — a message that reaches the DLQ unmasked is already a problem
regardless of what the reader does.

### 9. Chaos

Toxiproxy in front of Kafka. Pumba to kill a broker. `chaos-core`'s targeted
`@KafkaListener` exception injection from phase 2.

Alerts on **consumer lag** and **DLQ depth**.

## Key decisions

| Decision | Defence |
|---|---|
| Outbox over 2PC | XA across MySQL and Kafka is operationally awful and Kafka is not a good XA participant |
| Both polling and CDC | Turns an opinion into a measured comparison |
| Partition key = `paymentId` | Ordering is per-partition; this is what makes per-payment ordering real |
| Internal topic RF=3 explicitly | Defaults of 1 silently defeat broker-kill durability |
| Mask at produce time | DLQ messages persist longest and are read by humans |
| Choreography here | Few steps, clear events; orchestration earns its cost later |

## Exit criteria

- [x] Kill one broker under sustained load → **zero data loss**, producer keeps
      writing — `tools/loadtest/broker-kill.sh`. Measured **45 payments, 45
      events, 0 lost** across three windows. One broker `SIGKILL`ed: 6 partitions
      under-replicated, writes continued, 15/15 events published. A **second**
      broker killed, taking the cluster below `min.insync.replicas=2`: Kafka
      answered `NOT_ENOUGH_REPLICAS` and the 15 events waited in the outbox
      rather than landing on a single doomed replica. All drained on recovery,
      zero under-replicated partitions after, and `__consumer_offsets` held RF=3
      with full ISR throughout — the internal-topic trap the phase names
- [x] Inject 30% consumer failures via `chaos-core` → messages tier through retry
      topics → land in DLQ → replay them → **ledger converges to correct balances**
      — `tools/loadtest/retry-dlq.sh`, two arms. At p=0.3 over 150 payments the
      ladder decayed **47 → 17 → 4 → 0**: the injection rate three times over,
      every one of the 150 posted, and **nothing reached the DLQ**, because
      0.3⁴ = 0.81%. That is the result, and it is also why a second arm exists —
      the DLQ clause would otherwise have been ticked by a DLQ that was never
      written to. At p=1.0, **12 of 12** walked all three tiers into the DLQ with
      nothing posted, replayed in one call, and the books balanced: sum = 0, no
      event posted twice. Replaying the *same* records again produced **13
      duplicates and zero new entries**
- [x] A single SigNoz trace spanning the sync path **and** the async webhook
      delivery — `tools/loadtest/trace-propagation.sh` (6g) and
      `tools/loadtest/webhook-security.sh` (6h). Before: the ledger posted the
      event and appeared in **0 spans and 0 log lines** of the trace that caused
      it. After: **14 spans, five services, one trace** — `http post
      /v1/payments` at t+0ms, `outbox publish` at t+228ms, `payment.events
      process` at t+245ms, and the webhook to the merchant at t+271ms. The
      merchant's own endpoint recorded the same `traceparent` off the wire,
      which is the stronger half of the evidence: it is exactly what would be
      missing if the `RestClient` had been built without an observation
      registry. A deliberately failed delivery and its 5-second-tier redelivery
      are three spans of one trace, the second marked `Error`
- [x] DLQ messages contain no unmasked PII — asserted in the run rather than
      argued from the record definition: **0** digit runs of card length and **0**
      `cvv`/`expiry`/`pan` fields across the sampled DLQ payloads. The message
      carries a vault token, a BIN and a last-4 — the same ten digits phase 1
      already stores in plain text. It holds because masking happens at **produce**
      time; nothing in the DLQ path masks anything. The exception *message* is
      deliberately kept out of the log allowlist for the same reason: a
      `DeserializationException` carries the bytes it could not parse
- [x] Alerts on consumer lag and DLQ depth fire during the chaos run —
      `tools/obs/async-alert-drill.sh`, three arms, all firing and resolving.
      **Depth is not the number to page on**, and neither is the client-side lag
      metric. The unit began by finding there was no lag metric at all: 80 metric
      families on `ledger-notifier` and not one of them `kafka.consumer.*`,
      because a hand-built `ConsumerFactory` opts out of Boot's
      `MicrometerConsumerListener`. Adding it gave 60 series and was still not
      enough — freezing the consumer took those 60 series to **0**, so a `> 50`
      threshold cannot fire for the incident that matters most. `consumer-lag`
      therefore pairs a threshold with `alertOnAbsent`, and fires on **silence**
      in 390 s. `dlq-pending` thresholds `pending`, which returns to zero, and
      publishes `records`, which never does. Measured: arm 1 fired at t+180 s and
      resolved 210 s after the backlog cleared; arm 2 fired on no-data at
      t+390 s; arm 3 fired at t+180 s and resolved at t+510 s

- [ ] The saga compensates a capture the ledger could not record —
      `tools/loadtest/saga-reversal.sh`, three arms.
      **Written, not yet run**: the implementation is complete and unit-tested
      (343 tests, 0 failures) but the stack was unavailable when it was built, so
      there are no measured numbers and none have been invented. See
      [experiment 16](../experiments/16-compensating-reversal.md) for the
      hypotheses and what each arm asserts. Arm B is the one worth reading — it
      replays the DLQ *after* the reversal, which is a person using the phase-6f
      feature correctly and would silently corrupt two accounts without a
      tombstone

"Ledger converges to correct balances" is the criterion that actually tests the
saga and the retry tiers together.

## Traps

**Internal topic replication factor.** Covered above. The most common way a
"three-broker cluster" turns out to be a one-broker cluster for the things that
matter.

**Standalone Mongo.** Transactions fail with an error that does not obviously say
"you need a replica set".

**`rs.initiate()` on every restart.** The init container must be idempotent —
compose already wraps it in a `try/catch` on `rs.status()`.

**Blocking retries.** `@RetryableTopic` non-blocking retry exists for a reason;
a blocking retry stalls the whole partition behind one bad message. The converse
trap is applying that lesson everywhere: 6k's compensation topic uses a *blocking*
`DefaultErrorHandler` on purpose, because it carries at most one record per
incident and the ladder's cost — reordering, and four topics per listener — buys
nothing there.

**A compensation can be the incident.** A dead-lettered capture is not
automatically money that needs giving back. If the ledger posted it and the
*webhook* is what failed, the books are complete and reversing would take back
funds the ledger says are owed. Found while writing 6k rather than after: the
guard is `!ledger.hasEntryFor(eventId)`, and the arm that asserts it is the one
that makes the green result mean anything.

**A tombstone is the only thing that survives a helpful operator.** The DLQ
replay built in 6f will happily push a compensated capture back through days
later. `existsByEventId` is false — that event never posted, which is *why* it
was compensated — so every duplicate defence in the ledger says go ahead, and
`SUM(amount_minor)` stays at zero while two accounts are permanently wrong.

**`DltStrategy` defaults to `ALWAYS_RETRY_ON_ERROR`.** Found in 6f, and it is the
worst default in this phase. If the DLT handler throws, the record is republished
**to the dead-letter topic it is already on**, forever. Measured: four messages
became **10,306 records in three minutes**, and the only thing that stopped it was
each republish appending stack-trace headers until the record exceeded the
producer's `max.request.size`. Set `dltStrategy = FAIL_ON_ERROR`, and separately
make the handler incapable of throwing — a dead-letter queue that re-queues its
own failures is not a dead end.

**`autoCreateTopics = "false"` does not stop the BROKER.** It stops Spring. A
consumer *subscribing* to a name that does not exist is enough for a broker with
`auto.create.topics.enable=true` to create it at its own defaults. Measured: the
DLQ was deleted to clear a bad run and came back **RF=1, one partition**,
recreated by the service's own subscription before the topics script could run —
silently undoing 6a and 6d for the one topic nobody watches. The other half is
`allow.auto.create.topics=false` on every consumer.

**Retry topic names are derived, not chosen.** Spring builds them from the main
topic, the suffix and the backoff delay in milliseconds. Readable names like
`payment.events.retry.5s` are wrong by one character, and the punishment for
being wrong is the auto-creation trap above rather than an error.

**The DLT handler is inside your logging guardrails.** The 10,306-record loop
started because the handler passed field names that are not on the `LogFields`
allowlist and `LogEvent` threw — the phase-4 PII control working exactly as
designed, in the one place where an exception is most expensive.

**Trace context is a THREAD-LOCAL, and the outbox has no thread to hold it.**
The obvious fix — `KafkaTemplate.setObservationEnabled(true)` — is correct for a
publisher that sends on the request thread and is worse than nothing for a relay
that sends on a scheduler thread half a second later. It succeeds: it injects
well-formed headers naming the polling loop, produces a producer span, and yields
a trace that is internally consistent and describes nothing anybody asked about.
There is no error to find. Capture the context where it exists — inside the
payment's transaction, in the row — and read it back at publish time. Same
argument the outbox makes about the event, applied to the trace.

**A component can be traced, correlated, and exporting to nowhere.** Found in 6g:
`ledger-notifier` had observability-starter since phase 4 and had never exported a
span, because the compose override that points services at the collector lists
four services by name and the ledger is not one of them. The list was correct when
written; phase 6 changed what the payment path is and nothing came back to the
file. The symptom is the confusing one — the right `traceId` in the service's log
lines, and zero spans in ClickHouse — which reads as half-broken propagation and
is a missing endpoint.

**`balance += amount` on a JPA entity is a lost update, and double entry will
not catch it.** Found in 6j: the ledger's cached `balance_minor` had drifted
**1,911,000 minor units** from the sum of its own entries after two days of
phase-6 runs, because three consumer threads read-modify-write the same account
row and every payment touches `settlement:clearing`. The entries were correct
throughout, so the convergence check was green; the *sum of cached balances* was
also zero, because both legs of a posting are lost together and the losses arrive
in balanced pairs. Use `set balance = balance + :delta` so the arithmetic happens
inside the row lock, and check the cache against the entries separately - the
double-entry invariant is structurally incapable of seeing this.

**A hand-built `ConsumerFactory` has no Kafka metrics.** Boot attaches a
`MicrometerConsumerListener` to the factory it autoconfigures; build your own for
the deserializers and you silently lose every `kafka.consumer.*` meter, including
lag. Nothing warns. The symptom is an exit criterion about consumer lag with no
lag series to query, discovered six units later.

**The client-side lag metric goes quiet exactly when lag matters.**
`records-lag-max` is computed by the consumer from fetches the consumer made, so
a crashed, wedged or descheduled consumer publishes none — measured: 60 series to
0 the moment the process was frozen. A `> N` threshold on it is unfireable for
the worst incident it exists to catch, so the rule needs a no-data condition and
the metric needs a broker-side twin.

**The alert is judging a window that ended minutes ago.** Measured in 6i: the lag
rule fired when the live lag was 40, under its own threshold of 50, because
`evalWindow` (2 m) and SigNoz's undocumented `eval_delay` (2 m) put the evidence
four minutes behind the dashboard. Any drill that reads the current value to
decide whether the alert *should* have fired is asking the wrong question.

**A webhook dispatch inside `if (applied)` is a permanent drop.** The obvious
placement — only notify when the ledger actually posted something — makes a single
webhook failure final: the ladder redelivers, the idempotent post returns
`false`, the dispatch is skipped, and the merchant is never told.
`applied == false` means "the ledger already had this event", not "the merchant
already heard about it", and nothing in the type system distinguishes those.

**A merchant's endpoint is entitled to hang, and ordering is per partition.** A
webhook client with no read timeout stalls the consumer thread, which stalls
every payment that hashed to the same partition. That is the head-of-line
blocking the non-blocking retry ladder was built to prevent, reintroduced one
layer further out, by an HTTP client that looks nothing like a Kafka concern.

**Outbox relay double-publishing.** At-least-once is fine — that is what the
idempotent producer and consumer-side idempotency are for. Design for it rather
than trying to achieve exactly-once by hand.

**Trace context lost at the Kafka boundary.** It does not propagate for free.
Inject and extract explicitly, then verify with an actual end-to-end trace. The
verification is the load-bearing half: generate the trace id in the harness and
send it in, rather than making a request and looking for its trace afterwards. A
run that searches for the trace can only find one that exists, so it cannot fail
in the way that matters.

## Interview payload

Outbox + CDC as the answer to the atomicity question, with the trade-offs of
both variants measured rather than asserted.

**Be ready for:** *"Why not just use Kafka transactions?"* They give atomicity
between Kafka reads and Kafka writes — not between MySQL and Kafka. The database
write is outside the transaction, so the problem is untouched.
