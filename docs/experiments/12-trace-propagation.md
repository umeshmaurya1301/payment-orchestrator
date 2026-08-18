# 12 — Trace context across the Kafka boundary

**Phase 6g.** `tools/loadtest/trace-propagation.sh`

---

## Hypothesis

Written before any code changed.

Every service in this stack has had OpenTelemetry since phase 4, and the traces
are good — as far as the last HTTP hop. Phase 6 added a hop that is not HTTP, and
the phase plan's own trap list says the context will not survive it:

> **Trace context lost at the Kafka boundary.** It does not propagate for free.
> Inject and extract explicitly, then verify with an actual end-to-end trace.

So the prediction is that the ledger's work is invisible in the payment's trace.
Specifically:

1. A payment made with a known `traceparent` will show spans from
   `payments-edge`, `payment-orchestrator` and `psp-connector`, and **none** from
   `ledger-notifier`, even though the ledger demonstrably consumed the event.
2. The ledger's log lines for that event will carry **no** `traceId` at all —
   not a wrong one. MDC correlation hangs off an observation scope, and a
   `@KafkaListener` without observation enabled has none.
3. Fixing it will be two flags: observation on the `KafkaTemplate`, observation
   on the listener container.

Prediction 3 is the one worth writing down, because it is wrong and it is the
reason this phase is more than a configuration change.

## Setup

The usual way to check trace propagation is to make a request, find its trace in
the UI, and look at it. That is a demo: it cannot be scripted, it cannot fail,
and it proves one lucky case.

So the script **chooses the trace id**. It generates a W3C `traceparent` and
sends it with the payment. Spring's propagator continues an inbound trace rather
than starting a new one, so every span and every log line downstream should carry
an id the script already knows — no correlation, no searching, no argument about
which trace was the right one.

Three independent sources are then asked where that id appears:

| Source | Read via | Answers |
|---|---|---|
| Container logs | `docker logs \| grep <traceId>` | what an engineer greps during an incident |
| Spans | ClickHouse directly, `signoz_traces.distributed_signoz_index_v3` | the exit criterion |
| The ledger | `SELECT COUNT(*) FROM ledger_entry WHERE payment_id = …` | did the event actually arrive |

The third is not decoration. A run where the event never reached the ledger shows
zero ledger spans, which is exactly what a propagation failure looks like.

```
EVENTS_PUBLISHER=outbox docker compose -f docker-compose.yml \
    -f docker/signoz/payorch-obs.override.yml --profile async up -d
tools/loadtest/trace-propagation.sh 3
```

## Graph

The waterfall, from ClickHouse, offsets in ms from the first span of the trace.
**Before**, the last three rows did not exist.

```
   svc                    span                                kind      t+ms   dur
   ---------------------  ----------------------------------  --------  ----  ----
   payments-edge          http post /v1/payments              Server       0    97
   payments-edge          evalsha                             Client       5     1
   payments-edge          evalsha                             Client       7     0
   payments-edge          http post                           Client      28    60
   payment-orchestrator   http post /internal/v1/payments     Server      30    56
   payment-orchestrator   http post                           Client      56    14
   psp-connector          http post /internal/v1/authorize    Server      57    11
   psp-connector          evalsha                             Client      60     0
   psp-connector          psp.authorize                       Internal    62     6
   psp-connector          http post                           Client      62     6
   mock-psp-simulator     http post /psp/v1/authorize          Server      65     2
   ------------------------------- the merchant's request returns at 97ms -------
   payment-orchestrator   outbox publish                      Producer   408     9
   ledger-notifier        payment.events process              Consumer   418    30
```

The gap between 97 and 408 is the polling relay's interval, and it is worth
noticing that it is now **a visible fact in a trace** rather than a number from
`relay-comparison.sh`. Phase 6c measured polling at 260 ms p50 against CDC's 5 ms;
this is the same measurement, arrived at from the other direction, on one
payment, without a separate harness.

## Actual result

### A. Before: the trace stops at the broker

```
   WHERE THE TRACE ID APPEARS IN THE LOGS
   payments-edge                     1
   payment-orchestrator              2
   psp-connector                     1
   ledger-notifier                   0        <-- the boundary

   SPANS IN SIGNOZ FOR THAT TRACE
   payment-orchestrator              2
   payments-edge                     4
   psp-connector                     4

   THE ASYNC SPANS, IF ANY
   (none)

   ok   log lines on the sync side                     2
   XX   log lines on the ASYNC side                    0 (expected > 0)
   ok   services in the single trace                   3
   XX   ledger-notifier spans in that trace            0 (expected > 0)
```

The ledger posted **2 entries** for that payment while contributing zero spans
and zero log lines to the trace that caused them. Predictions 1 and 2 hold
exactly.

### B. Why two flags were not enough

Prediction 3 was wrong, and the reason is the outbox.

`KafkaTemplate.setObservationEnabled(true)` injects **the current** trace context
into the record. That works for the direct publisher, which sends on the request
thread while the trace is still live. The relay is not on that thread and is not
in that moment: it publishes on a scheduler thread, half a second later, or sixty
seconds later after a lease expiry, or after a restart — and in the CDC arm, from
a process that is not this JVM and has never heard of the request.

Turning the flag on there would have produced well-formed headers pointing at the
relay's own polling loop. Every event correctly traced, into a trace containing
nothing but the relay. That is **worse than no headers at all**: a broken trace
announces itself, a plausible one does not.

So the context stops being a thread-local and becomes a column
(`V11__outbox_traceparent.sql`), captured in `OutboxWriter` inside the payment's
transaction and read back by the relay. Which is the outbox's own argument — *if
two facts must agree, one transaction has to write both* — applied to the trace
instead of the payload.

Three delivery paths, three different answers to the same question:

| Path | How the header gets on the record | Span for the hop? |
|---|---|---|
| Direct publisher | `setObservationEnabled(true)`, one line | yes, free |
| Polling relay | reads the column, opens a child span, injects by hand | yes, `outbox publish` |
| Debezium CDC | `table.fields.additional.placement`, column → header | **no** |

The CDC arm's trade is real and was not obvious until it was written down.
Debezium *can* continue the trace itself — `tracing.span.context.field` — but
that needs the OpenTelemetry API on the Connect worker's classpath, which means a
custom image. Copying the column into a header gets the trace across for free and
gives up the span for the binlog hop, so the waterfall shows the request and then
the consume, with the CDC latency as an unexplained gap rather than a labelled
one. Verified live:

```
$ kafka-console-consumer --topic payment.events.cdc --property print.headers=true
  sent with 00-71d840c808e07b3994a94aa1b1c46c2f-46b48431aef3a5b0-01
  id:AaAVSHTLcoatSW7e8IqneQ==,traceparent:00-71d840c808e07b3994a94aa1b1c46c2f-07a2c18555dd128e-01
```

Same trace id, different span id — the header carries the orchestrator's span,
not the edge's inbound one, because that is what the column stored.

### C. After

```
   WHERE THE TRACE ID APPEARS IN THE LOGS
   payments-edge                     1
   payment-orchestrator              2
   psp-connector                     1
   ledger-notifier                   1

   SPANS IN SIGNOZ FOR THAT TRACE
   ledger-notifier                   1
   mock-psp-simulator                1
   payment-orchestrator              3
   payments-edge                     4
   psp-connector                     4

   THE ASYNC SPANS
     payment-orchestrator   outbox publish                     Producer
     ledger-notifier        payment.events process             Consumer

   ok   log lines on the sync side                     2
   ok   log lines on the ASYNC side                    1
   ok   services in the single trace                   5
   ok   ledger-notifier spans in that trace            1
```

**3/3 payments**, one trace each, five services, sync and async in the same
waterfall.

### D. The delivery actually worth tracing

A happy delivery is the case where somebody would have noticed the trace was
broken. The one worth tracing is the delivery that failed at 14:02 and succeeded
at 14:12 from a retry topic — that is where *"what happened to this payment"* is a
genuinely hard question.

So the second half of the run arms the chaos seam at p=1.0, sends one payment,
disarms the instant the first attempt fails, and lets the 5-second tier redeliver:

```
   THE LEDGER'S SPANS IN THAT ONE TRACE
     ledger-notifier   payment.events process                   Consumer
     ledger-notifier   payment.events.retry-5000 process        Consumer

   ok   the event was eventually posted                2
   ok   consumer spans in the one trace                2
   ok   a span from the retry topic itself             1
```

With the timings, and the status column:

```
   payment-orchestrator   outbox publish                      Producer    571     7  Unset
   ledger-notifier        payment.events process              Consumer    580     4  Error
   ledger-notifier        payment.events.retry-5000 process   Consumer   5691    28  Unset
```

The failure, the forward, and the redelivery 5.1 seconds later are three spans of
one trace. This came for free once the header was on the record: the
`DeadLetterPublishingRecoverer` copies the original headers onto the retry record,
so a message that reaches the 10-minute tier is still a descendant of the request
that created it eleven minutes earlier.

The DLQ replay path needed one deliberate line. `DlqAdmin.replayRecord` copies
`traceparent` verbatim — and **only** `traceparent`; the other DLQ headers are
forensics about a failure, and carrying them onto a fresh attempt would make the
replayed record claim to have already failed. A replay that started its own trace
would leave the original trace ending at the dead-letter with no resolution, which
is precisely the question the operator running the replay is about to be asked.

### E. A fifth thing that was doing nothing

The run above did not pass on the first try after the fix. It produced this,
which is the most confusing possible result:

```
   ledger-notifier   log lines carrying the trace id      1
   ledger-notifier   spans in that trace                  0
```

A log line carrying the right trace id proves the context crossed Kafka, was
extracted, and opened an observation scope. So the propagation worked and the
spans were missing, which is the opposite of every failure mode being tested for.

`docker/signoz/payorch-obs.override.yml` — the file that points a service at the
collector — listed four services, and `ledger-notifier` was not one of them. It
had been exporting spans to `http://localhost:4318` inside its own container
since phase 4, where nothing listens, in silence.

The list was **correct when it was written**. In phase 4 the ledger consumed
nothing and those four services were the whole payment path. Phase 6 made the
ledger part of a payment's life and nothing came back to this file. The service
whose build comment reads *"Phase 4. Traces and the trace-log correlation, so an
event consumed forty seconds after the payment can still be tied back to it"* was
the one service excluded from the file that makes tracing work.

That is the fifth component of phase 6 found present and inert, after the
`@RetryableTopic` needing an unmentioned scheduler bean, the header family that
returns null rather than failing, the broker that creates the topic you told
Spring not to, and the exposure list naming an endpoint with no registry behind
it.

## What surprised me

**The two-flag prediction was wrong in the interesting direction.** I expected the
work to be "turn on observation in two places". The flag is genuinely enough for a
synchronous publisher, and it is *actively harmful* for an asynchronous one —
because it succeeds. It produces headers, a producer span, and a trace that is
internally consistent and describes the polling loop. There is no error and
nothing to grep for. The only way to catch it is to already know which trace the
event should have joined, which is why this experiment generates the trace id
rather than discovering it.

**Decoupling has a cost denominated in context, not just in latency.** Every
argument for the outbox is about *removing* coupling between the payment and the
publish: different transaction, different thread, different time, in the CDC arm a
different process. Every one of those separations is also a separation the trace
has to be carried across by hand. The outbox trades a distributed-systems problem
for an observability problem, and phase 6b's writeup did not notice, because the
events were arriving and the balances were right.

**Boot 4 moved the bridge, and only the test noticed.** `TraceCarrierTest` builds
the same `Tracer` and `Propagator` Boot's autoconfiguration builds, deliberately,
because the claim under test is about the exact bytes of a `traceparent` surviving
a database column and no mock can fail that. It would not compile:
`io.micrometer.tracing.otel.bridge` was not on the classpath. Boot 4 split its
tracing autoconfiguration into `spring-boot-micrometer-tracing-opentelemetry`,
which carries the *configuration* — the composite propagator, the OTLP exporters,
the sampler properties — and not the bridge classes, which are still in
`micrometer-tracing-bridge-otel` and arrive transitively. Fourth instance in this
project of a Boot 4 module split that is invisible until something asks for a
class by name.

**The relay lag became a fact instead of a measurement.** Phase 6c built a whole
harness to compare polling against CDC and produced 260 ms p50 versus 5 ms. That
comparison is now legible in a single trace as the gap between the request
returning and `outbox publish` starting — 311 ms on the payment above. Nobody
planned that; it is a side effect of the publish having a span at all, and it is
the strongest argument in this phase for the extra work of the CDC arm being
worth a span of its own.

## Standing questions

- **Sampling.** `ObservabilityDefaults` has run at 100% since phase 4 with a note
  saying it lands "once a trace can be followed end to end". It now can. Sampling
  a message pipeline has a wrinkle HTTP does not: the sampling decision is made at
  the edge and stored in a column, so an unsampled payment stays unsampled through
  a retry eleven minutes later — which is correct, and also means the retry tiers
  will be the least-sampled part of the system precisely when they matter.
- **`tracestate` is deliberately not stored.** Only `traceparent`. Baggage would
  be a second route from a request into a Kafka header and then into a DLQ record
  read by a human, and this project has spent two phases keeping that path clean.
- **The webhook half of the exit criterion is still open.** The criterion says a
  single trace spanning the sync path *and the async webhook delivery*. Webhooks
  do not exist yet. The mechanism they will need is the one built here.
