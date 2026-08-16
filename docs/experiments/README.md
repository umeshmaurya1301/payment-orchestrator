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
| Bespoke seams | `chaos-core` `/actuator/chaosseams` | Sleep-in-held-lock, per-consumer exceptions |
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
| `tools/chaos/toxic.sh` | Toxiproxy toxics: latency, timeout, reset_peer, bandwidth |
| `tools/chaos/pumba.sh` | Process chaos: SIGTERM, SIGKILL, pause |
| `tools/obs/signoz.sh` | SigNoz lifecycle, plus `apply` — dashboards and alert rules pushed from files in `docker/signoz/` |
| `tools/obs/alert-drill.sh` | Drives chaos specifically to make every alert fire and resolve, and fails if one does not |

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
