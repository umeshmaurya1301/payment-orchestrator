# Chaos experiments

One page per experiment. The first is `00-baseline.md`, written in phase 2,
before any resilience component exists.

## Required structure

Every page carries the same five sections, and the order matters:

1. **Hypothesis** - written *before* the run. What is expected to break, in what
   order, and at roughly what numbers.
2. **Setup** - the exact chaos configuration, load profile and duration. Enough
   that the run can be reproduced.
3. **Graph** - the measurement.
4. **Actual result** - what happened, with numbers.
5. **What surprised you** - the gap between 1 and 4.

Section 5 is the point of the whole exercise. An experiment that confirms the
hypothesis exactly teaches nothing and usually means the hypothesis was written
after the run.

## Rules

- **One fault at a time.** Two active chaos sources means the cause of a failure
  cannot be attributed to either.
- **Hypothesis before experiment.** Writing the prediction down first is what
  makes the gap visible; it stops recall from quietly rewriting what was
  expected.
- **Chaos without concurrency is invisible.** A bulkhead only rejects when
  in-flight calls exceed its limit, and a breaker only trips on a *rate* of
  failures within a window. Faults must be applied under k6 load or they are
  merely faults, not chaos.

## Chaos layer map

| Layer | Tool | Injects |
|---|---|---|
| Downstream PSP | `mock-psp-simulator` | Business-level: latency, errors, hangs, duplicates |
| Network / connections | Toxiproxy | Latency, timeout, `reset_peer`, bandwidth |
| In-process beans | `chaos-core` `/actuator/chaosbeans` | Latency and exceptions on our own `@Service` / `@Repository` beans |
| Bespoke seams | `chaos-core` `/actuator/chaosseams` | Sleep-in-held-lock, per-consumer exceptions, at a chosen probability |
| Process / container | Pumba | `kill`, `pause`, SIGTERM |
| Concurrency | k6 | The load that makes any of the above chaotic rather than merely faulty |

> **Not Spring Boot Chaos Monkey.** The plan called for it as the in-process
> layer, with the caveat: verify Boot 4 compatibility on first use, and replace
> the layer rather than pin a service back to Boot 3. It is inert on Boot 4.1 -
> its config class matches unconditionally, prints no banner, and a 2000 ms
> latency assault produced a 76 ms call; its control endpoint is built on
> `@RestControllerEndpoint`, which Boot 4 no longer auto-configures a discoverer
> for, so it cannot be toggled either. A dependency that silently does nothing
> is worse than none: every experiment run against it would have produced a
> clean "no effect" that looked like a finding about the system. `chaos-core`
> implements the layer instead, in about 150 lines, and has tests that prove it
> actually injects something.

Toxiproxy breaks the **link**, Pumba breaks the **process**, Chaos Monkey breaks
the **bean**. They are not interchangeable.

## Tooling

| Script | Does |
|---|---|
| `tools/loadtest/run-experiment.sh` | One experiment end to end: resets every chaos layer, starts the metrics capture, runs the k6 profile, captures the recovery tail, resets again |
| `tools/loadtest/{smoke,ramp,spike,soak}.js` | The four load profiles |
| `tools/loadtest/fairness.js` | Two merchants, one flooding. The only profile that can answer whether per-merchant limiting works, because every other one sends as a single merchant |
| `tools/loadtest/burst.js` | N requests fired simultaneously through `http.batch`. Measures what a limiter admits when callers genuinely race, which sustained load does not |
| `tools/loadtest/capture-metrics.sh` | Samples every service's `/actuator/prometheus` into one CSV |
| `tools/loadtest/summarise-metrics.py` | The peaks a writeup needs, out of a 40,000-row CSV |
| `tools/loadtest/plot-metrics.py` | ASCII time series, so a graph survives in a diff |
| `tools/loadtest/plot-routing.py` | The traffic-shift graph: per-provider share and the end-user success rate on one clock |
| `tools/chaos/toxic.sh` | Toxiproxy toxics: latency, timeout, reset_peer, bandwidth |
| `tools/chaos/pumba.sh` | Process chaos: SIGTERM, SIGKILL, pause |
| `tools/obs/signoz.sh` | SigNoz lifecycle, plus `apply` — dashboards and alert rules pushed from files in `docker/signoz/` |
| `tools/obs/alert-drill.sh` | Drives chaos specifically to make every alert fire and resolve, and fails if one does not |
| `tools/loadtest/routing-experiment.sh` | Degrades a provider partway through a run already at steady state, so the clock for "how long did it take to notice" starts at the fault |
| `tools/loadtest/capture-routing.sh` | Per-provider attempt counts over time, read from `payment_attempt` - the traffic-shift graph's source |
| `tools/loadtest/strategy-demo.sh` | Runs each routing strategy in turn and prints where the traffic went, so "demonstrably different" is a distribution rather than a claim |
| `tools/loadtest/dualwrite-gap.sh` | Measures events lost to a broker outage, and waits for the relay to drain before calling a gap permanent |
| `tools/loadtest/broker-kill.sh` | SIGKILLs one broker, then a second, and checks that writes continue then are refused rather than lost |
| `tools/loadtest/relay-comparison.sh` | Polling relay vs Debezium CDC on one population of payments: publish lag and idle database load |
| `tools/kafka/topics.sh` | The phase-6 topics, with RF=3 AND min.insync.replicas=2 - the second half being what makes the first mean anything |
| `tools/loadtest/failover-safety.sh` | Asserts failover fires only when nothing was sent, and that an ambiguous failure lands in `UNKNOWN` with one provider tried |
| `tools/loadtest/retry-dlq.sh` | The retry ladder end to end: transient failures at 30% to measure where they land, permanent failures to fill the DLQ, then replay and re-check convergence |
| `tools/loadtest/trace-propagation.sh` | Sends a payment with a trace id it CHOSE, then asks logs, ClickHouse and the ledger where that id appears. Also fails one delivery on purpose, to check the retry topic stays in the same trace |
| `tools/loadtest/webhook-security.sh` | Forges, tampers with and replays webhooks against a merchant endpoint written in another language, across four receiver configurations |
| `tools/obs/async-alert-drill.sh` | Makes the lag and DLQ alerts fire and resolve three ways: a slow consumer, a frozen one, and unhandled dead letters. Refuses to start unless both rules are already quiet |
| `tools/loadtest/capture-ledger.sh` | Authorizes, captures, and checks the clearing account against an exact expected total - which is what caught a two-day silent balance corruption |

Bean-level chaos and the bespoke seams are actuator endpoints contributed by
`infra-core/chaos-core`: `/actuator/chaosbeans` and `/actuator/chaosseams`.

**Always drive chaos through `run-experiment.sh`.** It resets both layers before
and after every run. Forgetting to reset is the single most common way an
experiment is silently ruined, and it does not announce itself - the run
completes, the numbers look interesting, and they are describing two faults.

## Index

| # | Experiment | Phase | Headline |
|---|---|---|---|
| — | [Hypotheses](hypotheses.md) | 2 | Predictions, written before the runs and left unedited |
| 00 | [Baseline](00-baseline.md) | 2 | The system collapses with no chaos at all: 1,037 threads queued for 20 connections at 200 rps, and an OOM at 500 |
| 01 | [Deadline budget](01-deadline-budget.md) | 3a | Against a provider that never answers: 2,112 payments stranded in `AUTHORIZING` becomes 2,372 recorded `UNKNOWN`, and throughput rises 60% |
| 02 | [Retry](02-retry.md) | 3b | Uncapped retries buy 61%→94% success and cost 54% more load on a failing provider; the 10% budget takes 67% for 12% |
| 03 | [Circuit breaker](03-circuit-breaker.md) | 3c | Against a dead provider: 94% less load on it, and 2,701 unresolvable `UNKNOWN` payments become 4 |
| 04 | [Bulkhead](04-bulkhead.md) | 3d | The first component the experiment did not justify: 92% less provider load, but 100%→6.8% success against a merely slow provider, and the edge still OOM'd. Semaphore beats thread pool 3.7× on tail latency |
| 05 | [Rate limiters](05-rate-limiters.md) | 3e | The layer that finally stopped the edge dying: 1 OOM → 0, 5,858 unanswered requests → 0, p99 22.5s → 3.2s. Checking the shared limit before the per-merchant one cost a blameless merchant 75% of their traffic |
| 06 | [Dynamic config](06-dynamic-config.md) | 3f | One UPDATE, mid-run, no restart: provider throughput 8.0/s -> 51.4/s, both sides Little's law to within 3%. The extra capacity stopped at the contracted 50 TPS - widening a limit hands the constraint to the next layer, not to infinity |
| 07 | [Dashboards and alerts](07-alerts.md) | 4e | Three of four alerts could not have fired. One queried a counter registered as a gauge (`increase` = 36 over a window where the value never left 4); one thresholded a condition four nested limiters make unreachable; one measured HTTP 5xx, which stayed at **zero while 99.7% of payments failed** - because a decline is a `201`. That last one found there was **no payment-outcome metric at all** |
| 08 | [Log sampling](08-log-sampling.md) | 4f | Keeping every line costs 4.00 lines / 2,514 bytes per payment - 75 MB/minute at 500 rps. Trace-based sampling at 1% removes 97.6%, and keeps **100% of errors** (2,325 lines against 2,315 failed payments). The finding: sampling silently defeats the PAN-leak test, which would scan 1% of the lines and still report green |
| 09 | [Health-based routing, failover and strategies](09-health-routing.md) | 5a-5d | Static routing sent a provider degraded to 80% errors **100%** of the traffic and success collapsed 99.7% -> 4.2%. Health-weighted routing moves it in **7s** and holds 97.5% -> 91.2%. Routing on health ALONE cost 5.8 points of steady-state success - "healthy" and "preferred" are different questions - and a generous half-open score produced a 12s oscillation. Failover refuses to fire on an ambiguous failure - and the drill proving it failed by PASSING three times first |
| 10 | [The outbox](10-outbox.md) | 6a-6d | A 30s broker outage cost **20 of 60** payments their events, permanently - every one AUTHORIZED, every one a 201, no outage visible. The outbox closes it to zero, and its own machinery introduced two worse bugs first: the relay's claim query blocked the payment path (`Lock wait timeout` on the payment's own commit), and Spring's `@Transactional` was silently inert on a self-invoked method. CDC beats polling 5ms vs 260ms p50 and is still not the default. Killing two brokers proves min.insync.replicas earns its keep: `NOT_ENOUGH_REPLICAS`, and the events wait in MySQL |
| 11 | [Retry ladder and DLQ](11-retry-dlq.md) | 6f | At a 30% per-delivery failure rate the ladder decays **47 → 17 → 4 → 0** over 150 events - the injection rate three times over - and **nothing reaches the DLQ**, because 0.3⁴ = 0.81%. Which is why a second arm exists: the DLQ criterion would otherwise have been ticked by a DLQ nobody wrote to. Building it produced a three-part cascade that turned **4 messages into 10,306 DLQ records in three minutes**, triggered by the phase-4 PII allowlist working *correctly* inside the one handler that must never throw, amplified by a default named `ALWAYS_RETRY_ON_ERROR` whose dead-letter target is the dead-letter topic itself. Deleting the topic to clean up let the broker recreate it at **RF=1** - `autoCreateTopics=false` stops Spring, not the broker. And the fixed run passed all twelve assertions with **every forensic header blank** |
| 12 | [Trace across Kafka](12-trace-propagation.md) | 6g | The ledger consumed the event, posted it correctly, and appeared in **0 spans and 0 log lines** of the trace that caused it. The expected fix - turn on observation in two places - is enough for the direct publisher and *actively harmful* for the outbox: the relay publishes on a scheduler thread, so it would have injected its own polling loop and produced a trace that is internally consistent and describes nothing. The context has to become a **column**, captured inside the payment's transaction, which is the outbox's own argument applied to the trace. After: one trace, **five services**, `outbox publish` at t+408ms and `payment.events process` at t+418ms - and a failed delivery plus its `retry-5000` redelivery 5.1s later as three spans of one trace. Then the confusing part: right trace id in the ledger's logs, zero ledger spans in ClickHouse - the attach override has listed four services since phase 4 and the ledger is not one of them |
| 13 | [Webhook signing](13-webhook-security.md) | 6h | Unsigned, the merchant's endpoint accepted **7 of 7** deliveries: one real, one forged `AUTHORIZED` for **INR 500,000** on a payment that exists in no database here, and five replays of a captured one. Signed, every forgery is refused - including a captured MAC with the timestamp rewritten, which is why `t` is inside the signed material. And then the finding: an **unmodified replay inside the window is accepted**, because everything about it is genuine. A tolerance window makes a capture *perishable* (2s window → refused after 5s); what actually refuses a replay is the receiver remembering the event id, which is on the merchant's side of the integration. The verifier is Python written from the header format, not from `WebhookSigner.java` - two halves of one codebase always agree, including when both are wrong. Retries needed no new machinery: a failed delivery throws onto phase 6f's ladder, which is safe only because 6e made the ledger idempotent |
| 14 | [Lag and DLQ alerts](14-async-alerts.md) | 6i | The metric the criterion asks for did not exist: 80 metric families on `ledger-notifier` and **not one of them was lag**, because a hand-built `ConsumerFactory` silently opts out of every `kafka.consumer.*` meter. Then the reason one metric is not enough - freezing the consumer took the client-side lag from **60 series to 0**, so a `> 50` threshold has nothing to be true of during the *worse* incident. Only `alertOnAbsent` catches it, in **6.5 minutes**. The slow-consumer arm fired at t+180s when live lag was **40, below the threshold** - it was judging a window that ended two minutes earlier, where lag was 160. And the first run was compromised by its own subject: preflight found `consumer-lag` already firing on no-data, so arm 1 would have passed instantly, proving nothing |
| 15 | [Capture, and the drifted balances](15-capture-and-drift.md) | 6j | Capture existed on the simulator since phase 1 and nothing had ever called it. Building the path gave the ledger a second movement per payment, which made `settlement:clearing` mean something new: **the outstanding authorized-but-uncaptured exposure**, negative on authorize and back to zero on capture. Then arm A failed - 12 payments captured, 8 payments' worth of movement - and the cause was **1,911,000 minor units of drift** between the cached `balance_minor` and the sum of its own entries, from a read-modify-write lost update across three consumer threads all racing on the clearing row. The entries were correct the whole time, so phase 6e's convergence check was green throughout; the *sum of cached balances* was also zero, because both legs of a posting are lost together and the losses arrive in balanced pairs. **Two independent-looking invariants, both passing, every account wrong.** Also measures the gap the saga owes: capture succeeds at the provider, the ledger cannot post, and the books balance perfectly while being wrong about the world |
