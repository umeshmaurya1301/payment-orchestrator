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
| `tools/loadtest/capture-metrics.sh` | Samples every service's `/actuator/prometheus` into one CSV |
| `tools/loadtest/summarise-metrics.py` | The peaks a writeup needs, out of a 40,000-row CSV |
| `tools/loadtest/plot-metrics.py` | ASCII time series, so a graph survives in a diff |
| `tools/chaos/toxic.sh` | Toxiproxy toxics: latency, timeout, reset_peer, bandwidth |
| `tools/chaos/pumba.sh` | Process chaos: SIGTERM, SIGKILL, pause |

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
