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
  `infra-cryptography` in your existing `Infra-Core` repo already has an
  `HmacService` worth lifting.

### 5. Tiered retry topics

`5s` → `1m` → `10m` → DLQ, plus an admin replay endpoint.

Non-blocking retry: a failing message must not head-of-line block its partition.

### 6. Saga

For capture → ledger → notify, with compensating reversal on failure.

**Build choreography; be able to argue orchestration.** Choreography suits this
shape (few steps, clear events). Orchestration wins when the flow grows branches
and you need one place to see the state. Know which you would pick at 15 steps.

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
- [ ] A single SigNoz trace spanning the sync path **and** the async webhook delivery
- [x] DLQ messages contain no unmasked PII — asserted in the run rather than
      argued from the record definition: **0** digit runs of card length and **0**
      `cvv`/`expiry`/`pan` fields across the sampled DLQ payloads. The message
      carries a vault token, a BIN and a last-4 — the same ten digits phase 1
      already stores in plain text. It holds because masking happens at **produce**
      time; nothing in the DLQ path masks anything. The exception *message* is
      deliberately kept out of the log allowlist for the same reason: a
      `DeserializationException` carries the bytes it could not parse
- [ ] Alerts on consumer lag and DLQ depth fire during the chaos run — the
      metrics now exist (`payorch.ledger.dead_lettered`, `payorch.ledger.retried`,
      `/actuator/dlq` reporting records/pending/replayed separately), but no
      SigNoz rule queries them yet. Note for that work: **depth is not the number
      to page on.** Records are never removed from a log-structured DLQ, so a
      rule on record count fires permanently after the first incident; `pending`
      is the number that returns to zero when somebody fixes it

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
a blocking retry stalls the whole partition behind one bad message.

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

**Outbox relay double-publishing.** At-least-once is fine — that is what the
idempotent producer and consumer-side idempotency are for. Design for it rather
than trying to achieve exactly-once by hand.

**Trace context lost at the Kafka boundary.** It does not propagate for free.
Inject and extract explicitly, then verify with an actual end-to-end trace.

## Interview payload

Outbox + CDC as the answer to the atomicity question, with the trade-offs of
both variants measured rather than asserted.

**Be ready for:** *"Why not just use Kafka transactions?"* They give atomicity
between Kafka reads and Kafka writes — not between MySQL and Kafka. The database
write is outside the transaction, so the problem is untouched.
