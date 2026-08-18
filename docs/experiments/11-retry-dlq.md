# 11 — The retry ladder, the DLQ, and replay (phase 6f)

Phase 6e ended with a number nobody had asked for: **312 events silently
skipped**. A deserialization fault exhausted Spring's default error handler,
which retries nine times in quick succession and then logs and moves on. The
ledger was short and nothing said so.

This phase replaces that with four topics and three waits, then measures where
failures actually land.

> **Headline.** At a 30% per-delivery failure rate the ladder absorbs almost
> everything: of 150 events, **47 entered tier 1, 17 tier 2, 4 tier 3, and none
> reached the DLQ.** Every one of the 150 posted to the ledger.
>
> Getting there produced a three-part cascade in which each defect was survivable
> alone and the combination turned **four messages into 10,306 DLQ records in
> three minutes** — starting from the phase-4 PII guardrail doing its job
> correctly.

---

## Hypothesis

Written before the runs.

> The ladder is mechanical: publish forward on failure, wait, try again. I expect
> the interesting part to be the *distribution* — roughly 30% of messages into
> tier 1, 30% of those into tier 2, and so on, so under 1% should reach the DLQ.
>
> **Least confident about:** whether `@RetryableTopic` is as drop-in as it looks.
> I expect one surprise in the wiring and I expect it to be about topic naming.

---

## A. The ladder

```
payment.events                      first attempt
  → payment.events.retry-5000        5 seconds later
  → payment.events.retry-60000       1 minute later
  → payment.events.retry-600000     10 minutes later
  → payment.events.dlq              kept 30 days, read by a human
```

**Non-blocking, and that is the whole point.** The obvious implementation —
catch, sleep, retry — stalls the partition. Ordering is per partition and the key
is `paymentId`, so one message taking eleven minutes to give up would hold up
every other payment that hashed to the same partition for eleven minutes.
Publishing forward to a delay topic hands the wait to a different consumer and
lets the main partition advance immediately.

The mechanism underneath is worth knowing because it explains the cost: the
container polls a retry topic, sees a record whose backoff timestamp is in the
future, **pauses the partition**, seeks back to that offset, and hands a resume
task to a scheduler. Nothing is held open for ten minutes. The broker does not
care. What it does cost is a `RetryTopicSchedulerWrapper` bean — without one the
context refuses to start, which is the correct failure and not an obvious one.

**The names are derived, not chosen.** Spring builds them from the main topic,
the suffix, and the backoff delay in milliseconds. The tier topics were
originally called `payment.events.retry.5s` and friends, which read better and
were wrong — see section C.

**What is not retried:** a `DeserializationException` goes straight to the DLQ.
Spending eleven minutes on a message that cannot be parsed delays the moment a
human sees the poison message and changes nothing.

---

## B. Where 30% of failures actually land

`tools/loadtest/retry-dlq.sh`, arm 1. 150 payments, seam armed to fail 30% of
deliveries, each retry rolling independently.

| | count | share of the rung above |
|---|---|---|
| events published | 150 | |
| entered tier 1 (5s) | **47** | 31.3% |
| entered tier 2 (1m) | **17** | 36.2% |
| entered tier 3 (10m) | **4** | 23.5% |
| reached the DLQ | **0** | 0% |
| posted to the ledger | **150** | |

The decay is the injection rate, three times over, which is the cheapest possible
evidence that the ladder is real rather than configured. The predicted DLQ rate is
0.3⁴ = 0.81%, or 1.2 messages out of 150; the run produced 0, which is what a
Poisson mean of 1.2 does about 30% of the time.

**The ladder absorbed 100% of failures.** That number is the point of the phase
and it is also the reason arm 2 has to exist — see the header of the script. A run
that stopped here would have reported the DLQ exit criterion satisfied by a DLQ
that was never written to and a replay endpoint that never executed.

### An accounting discrepancy, left open

The tier entries sum to 47 + 17 + 4 = **68 forwards**, but the seam recorded
**60 injections**. Every forward should be caused by exactly one failure, so
these should be equal.

The counter is not the problem: arm 2 ran the same instrument at p=1.0 and
recorded **36 injections against exactly 36 forwards** — 12 through each of three
tiers — so it is exact when the failure rate is deterministic. Nor is it an
unhandled exception in the listener; the only exception class in the logs for the
window is `ConnectException` from the OTLP exporter, which never reaches the
listener.

That leaves eight forwards in arm 1 with no recorded cause. It is recorded here
rather than rounded away, because a number that does not add up is the honest
starting point for the next run and the alternative is a writeup that quietly
picks whichever of the two figures reads better.

---

## C. The three-part cascade

Each of these is survivable alone. Together they turned four messages into
10,306 records.

### 1. The PII guardrail fired, in the worst possible place

The dead-letter handler logged the original topic, exception type and exception
message. `LogEvent` enforces the `LogFields` allowlist, and those names were not
on it:

```
IllegalArgumentException: 'originalTopic' is not in the log field allowlist.
Add it to LogFields only after checking it cannot carry PII or cardholder data.
```

**This is the phase-4 control working exactly as designed.** An allowlist that
fails loudly is the whole reason it was chosen over a denylist. It just happened
to fail inside the one method whose job is to be where failures stop.

Adding the fields was a moment's thought, and the thought was worth having. Two
of the three went in — `sourceTopic` is a Kafka topic name and `failureType` is a
Java class name, both chosen by us rather than by a payload. **The exception
*message* did not.** A `DeserializationException` carries the bytes it could not
parse, so that field is a direct route from a malformed message to a card number
in a log index that is retained, indexed and searchable. It stays available
through `/actuator/dlq`, where somebody is deliberately looking at one record on
the management port.

### 2. `DltStrategy` defaults to `ALWAYS_RETRY_ON_ERROR`

So the record whose DLT handling threw was republished **to the dead-letter topic
it was already on**. Consumed again, threw again, republished again.

```
Record: topic = payment.events.dlq, partition = 3, offset = 9 ...
  threw an error at topic payment.events.dlq and won't be retried.
  Sending to DLT with name payment.events.dlq.
```

Read that last line twice. The DLT of the DLT is itself.

| | |
|---|---|
| messages that reached the DLQ legitimately | **4** |
| records in the DLQ three minutes later | **10,306** |
| what stopped it | the producer's `max.request.size` |

It stopped because **each republish appends stack-trace headers**, so the record
grows every lap until the producer refuses to send it. A loop that terminates by
exhausting a size limit is not a loop that terminated safely — it terminated
after writing ten thousand copies of a payment event to a topic with 30-day
retention.

`dltStrategy = FAIL_ON_ERROR` closes it. The handler body is also wrapped so it
cannot throw at all: a method whose entire job is to be where failures stop must
not have a failure mode of its own.

**And the wrap needed a counter.** A catch-everything around the last line of
defence will happily hide the exact bug it exists to survive — a run with a
broken DLQ log line would look identical to a healthy one. So
`payorch.ledger.dlt_log_failed` exists, must be zero, and is asserted at zero in
`PaymentEventConsumerTest`.

### 3. Deleting the topic to clean up made it worse

The DLQ was deleted to clear the 10,306 junk records. It came back as:

```
Topic: payment.events.dlq   PartitionCount: 1   ReplicationFactor: 1
```

**`autoCreateTopics = "false"` stops Spring. It does not stop the broker.** A
consumer *subscribing* to a name that does not exist is enough for a broker with
`auto.create.topics.enable=true` to create it, at its own defaults — and the
service's own DLQ container did so within seconds of restarting, before the
topics script could run.

The result is the failure shape this project keeps meeting: the topic is present,
the consumer is happy, health is green, and the one topic in the system that is
read days after the fact is the only one with no replication. Phases 6a and 6d
were spent proving this cluster survives losing a broker; this quietly exempted
the DLQ from that.

The other half of the fix is `allow.auto.create.topics=false` on every consumer,
including the replay consumer inside `DlqAdmin` — an admin endpoint that silently
recreates the DLQ without replication while reporting depth 0 would be the worst
possible place for it to happen.

---

## D. The DLQ, and what a record carries

Arm 2 arms the same seam at p=1.0, so nothing can succeed and every message must
walk the whole ladder.

| | |
|---|---|
| events published | 12 |
| entered tier 1 (5s) | **12** |
| entered tier 2 (1m) | **12** |
| entered tier 3 (10m) | **12** |
| reached the DLQ | **12** |
| posted to the ledger while failing | **0** |

Nothing dropped, nothing half-applied. The seam is reached *before* the ledger
write, so a failed delivery leaves nothing to clean up — failing after the write
would have re-tested phase 6e's unique constraint instead of the ladder.

A record in the DLQ carries its own history:

```
attempts   5
from       payment.events
exception  org.springframework.kafka.listener.ListenerExecutionFailedException
message    Listener failed; chaos injected at seam 'ledger-consumer'
```

**Two things about those headers were wrong on the first pass, and both were
silent.** `KafkaHeaders` defines two families — `kafka_original-topic` and
`kafka_dlt-original-topic` — that look interchangeable. The retry-topic machinery
writes the plain one. Reading the `DLT_*` family does not fail; every field comes
back null, so the forensic block printed `None` for everything while **every
assertion in the run still passed**, because depth is correct whether or not a
record can say anything about itself. And `retry_topic-attempts` is a raw
big-endian int in four bytes rather than text, written once per hop, so rendering
it as a string produces control characters.

`tools/loadtest/retry-dlq.sh` now asserts that DLQ records carry their forensics,
because the alternative is a dead-letter queue that is a list of payloads somebody
has to guess about.

### PII

The exit criterion, checked rather than asserted from the record definition:

```
ok   card numbers in DLQ payloads          0
ok   cvv/expiry/pan fields in DLQ          0
```

The payload is a token, a BIN and a last-4 — the same ten digits phase 1 already
stores in plain text — with no PAN, no CVV and no expiry. That holds because the
event is masked at **produce** time; nothing in the DLQ path masks anything, and
nothing should, because a message that reaches the DLQ unmasked is already a
problem regardless of what the reader does.

---

## E. Replay

```
replayed 12 record(s) to payment.events in 6626ms, 0 failed
  from payment.events: 12

ok   events recovered by the replay        12
ok   DLQ has nothing left pending           0

ok   double-entry invariant (sum=0)         0
ok   events posted more than once           0
ok   balances sum to zero                   0
```

**The ledger converged.** Twelve events that had been through eleven minutes of
retries and a dead-letter topic posted correctly on replay, and the books balanced
afterwards.

Then the interesting half — replaying records that were **already posted**:

| | before | after |
|---|---|---|
| ledger entries | 1,662 | **1,662** |
| distinct events | 831 | **831** |
| sum of all entries | 0 | **0** |
| duplicates recognised | 0 | **13** |

Thirteen records replayed, thirteen duplicates, zero new entries. The unique
constraint in `LedgerPosting` is what makes replay an operation somebody can run
twice during an incident without thinking hard about it — which is the only kind
of recovery tool worth having at 3am.

**Replay republishes the original bytes to the main topic**, not to the tier the
record failed on — a replay is a fresh attempt, and dropping it back into
`retry-600000` would give the operator a ten-minute wait for a fix they just
deployed.

It reads with `ByteArrayDeserializer` rather than parsing. Two reasons: the
replayed message is then byte-identical to the one that was dead-lettered, and a
poison message — the thing most likely to be sitting in a DLQ — can be replayed at
all.

**Replay commits offsets under its own group**, `ledger-dlq-replay`, so running
it twice does not resend everything. The records stay in the topic either way;
the offset is a bookmark, not a delete. `peek` deliberately does *not* commit —
looking at the queue during an incident must never be the reason a message is
skipped.

---

## F. A fourth thing that was doing nothing

The consumer's counters are the whole basis for alerting on this ladder, so they
were checked rather than assumed — and `/actuator/prometheus` on
`ledger-notifier` returned **404**.

`application.yml` has listed `prometheus` in
`management.endpoints.web.exposure.include` since phase 0. The service simply
never had `micrometer-registry-prometheus` on its classpath, and without a
registry the endpoint is not contributed at all. Meanwhile `/actuator/metrics`
lists every meter perfectly, because those live in the `SimpleMeterRegistry` Boot
falls back to.

So the counters existed, were correct, and were unscrapeable — and the only
symptom was an exposure list naming an endpoint that does not exist. Every other
service has had the dependency since phase 2.

| | |
|---|---|
| `/actuator/metrics` names `payorch.*` | 5 meters |
| `/actuator/prometheus` on `payment-orchestrator` | 676 lines |
| `/actuator/prometheus` on `ledger-notifier` | **404** |

Had the alerting criterion been ticked from the metric list rather than from a
scrape, it would have been ticked against a service nothing can scrape.

---

## What surprised me

**That the safety net had a longer fall than the thing it was catching.** The
retry ladder itself behaved exactly as predicted on the first run — the 30% decay
appears three times, cleanly. Every defect was in the machinery around it, and
each one alone was survivable: a log field not on an allowlist, a default called
`ALWAYS_RETRY_ON_ERROR`, a broker setting nobody set. Together they wrote 10,306
copies of a payment event to a topic with 30-day retention, and the thing that
stopped it was a size limit rather than any control I had put there.

**That the guardrail firing correctly was the trigger.** The `LogFields`
allowlist did precisely what phase 4 built it to do. There is no version of this
where the allowlist is wrong. But a control that throws is a control that can
throw *inside a handler whose job is to be where exceptions stop*, and I had not
thought about which of my own methods must never fail.

**That a full green run can have blank forensics.** Twelve assertions passed
against a DLQ whose every record said `None` for where it came from and why it
failed. Depth was right, the ladder was right, the replay was right, and the one
thing a human would actually open the DLQ to read was missing. Third time in this
project that "the numbers are correct" and "the feature works" turned out to be
different claims.

**That the discrepancy I cannot explain is in the good arm.** Arm 2, where
everything fails, accounts perfectly: 36 injections, 36 forwards. Arm 1 is off by
eight and I do not know why.

**That four separate things in one phase were present and inert**, and every one
of them looked configured: a `@RetryableTopic` annotation that needs a scheduler
bean it does not mention, a header family that returns null instead of failing, a
broker that creates the topic you told Spring not to create, and an exposure list
naming an endpoint with no registry behind it. The count for phase 6 as a whole is
now seven. The pattern is stable enough to be a rule: **the failure mode of
configuration is silence**, and the only defence is to count what comes out the
other end.

---

## Standing questions

- **Consumer lag and DLQ depth are not alerted on yet.** The metrics exist and,
  since section F, are actually scrapeable; the SigNoz rules do not. When they are
  written: **depth is the wrong number.** Records are never removed from a
  log-structured DLQ, so a rule on record count fires permanently after the first
  incident. `pending` is the one that returns to zero when somebody fixes it.
- **Trace context still does not cross Kafka.** A record dead-lettered eleven
  minutes after the payment starts its own trace.
- **DLQ records are never deleted.** 30-day retention is the only bound, and the
  loop above showed how fast that can be filled.
