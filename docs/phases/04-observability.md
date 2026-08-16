# Phase 4 — Observability

| | |
|---|---|
| **Estimate** | ~2 weeks |
| **Depends on** | phase 3 |
| **Delivers** | a trace spanning four services with correlated logs, and the PAN-leak build test |

## Goal

See everything phase 3 does.

## Why here

Phase 3 built behaviour that is invisible. Phase 5 needs rolling per-provider P99
as a routing input, and cannot be built until that number exists and is
trustworthy.

The logging *foundation* was built in phase 0 precisely so this phase is a
pipeline problem rather than a codebase-wide refactor.

## Prerequisites

- Phase 3 complete, three providers with distinct personalities
- `observability-starter` empty shell in place
- Structured JSON logging already emitting (phase 0)

## Implementation

### 1. SigNoz on an external Docker network

Attach services to it rather than merging compose files. SigNoz ships its own
multi-container stack (ClickHouse, collector, query service, frontend);
vendoring a copy means re-reconciling it on every upgrade.

```yaml
networks:
  signoz-net:
    external: true
```

ClickHouse is the single largest memory consumer in the budget (3-4 GB), which
is why this sits behind the `obs` profile and does not run during phases 1-3.

### 2. Tracing

OpenTelemetry auto-instrumentation plus **manual spans on the interesting
seams** — provider call, tokenization, detokenization, state transition.
Auto-instrumentation gives you HTTP and JDBC; it does not know what a payment is.

### 3. Metrics

Micrometer timers with **percentile histograms — buckets, not pre-computed
percentiles.** This is not a formatting preference; see the interview payload.

Rolling per-provider P99 over a sliding window. **This becomes phase 5's routing
input**, so build it as a reusable component, not a dashboard query.

### 4. Dashboards and alerts

Dashboards: per-provider latency distribution, error rate, breaker state,
bulkhead saturation.

Alerts: breaker open, P99 breach, error-rate threshold, egress limiter saturation.

An alert that never fires during a chaos run is not configured correctly.

### 5. Distributed logging pipeline

**Trace ↔ log correlation.** The OTel Logback appender injects `traceId` and
`spanId` into MDC automatically; ship logs to SigNoz over OTLP. A log line and
its trace become one click apart. **This is the highest-value item in the phase.**

`LogFields` already reserves `TRACE_ID` and `SPAN_ID`.

**Log sampling.** At 500 rps, logging every request costs more than serving it.
Trace-based sampling: retain 100% of errors and slow requests, ~1% of successes.
Tail-based sampling if you want the harder version.

**Dynamic log levels** via `/actuator/loggers` — already exposed and verified
working in phase 0. During a chaos run you want DEBUG on one service for 90
seconds without a restart.

**Standard field schema** — `LogFields` already defines it and `LogEvent`
enforces it. Consistent names make SigNoz queries possible; ad-hoc names make
them useless.

### 6. The PAN-leak build test

Runs the k6 suite against a live stack, captures all container log output, and
**fails the build** if any Luhn-valid card number, unmasked VPA or full mobile
number appears.

This is the difference between claiming you handled PII and having enforced it.
`Masking.isLuhnValid` already exists; phase 1's exit-criterion script is the seed.

Wire it so it fails loudly. A leak test that is allowed to warn is decoration.

## Key decisions

| Decision | Defence |
|---|---|
| Histogram buckets, not pre-computed percentiles | Percentiles do not aggregate — see below |
| SigNoz on an external network | Independently upgradeable; no vendored copy to reconcile |
| Sampling: 100% errors, ~1% successes | Retains the lines you actually read; drops the ones you never will |
| Rolling P99 as a component | Phase 5 consumes it programmatically, not as a chart |
| PAN-leak test fails the build | A warning-only control is not a control |

## Exit criteria

- [ ] A single trace in SigNoz spanning edge → orchestrator → connector →
      simulator with per-hop timing
- [ ] That trace's log lines correlated to it by `traceId`
- [ ] Alerts firing during a chaos run and resolving after
- [x] PAN-leak test green in CI, and demonstrably red when a PAN is injected
      — `./gradlew panLeakTest` drives the k6 smoke suite against a live stack,
      scans the database dump and every container's output, and fails the build.
      It self-tests first: the scanner is pointed at a known PAN, VPA and mobile
      number and required to go red before it is trusted to report green.
      Demonstrated red by sending a card number in `merchantReference`, which
      found a **real leak** — merchant free text was persisted verbatim into
      `payment` and into the idempotency replay cache. Now redacted at the edge.

Test the failure path. A green leak test that cannot go red proves nothing.

## Traps

**Averaging P99 across instances.** The headline mistake. See below.

**Instrumenting everything.** Auto-instrumentation plus a span per method
produces traces nobody can read and a bill nobody wants. Span the seams.

**Sampling before you have errors to sample.** Turn sampling on after
correlation works, or you will debug the pipeline with 1% of the evidence.

**Cardinality explosions.** `paymentId` as a *metric tag* creates one time series
per payment and will take ClickHouse down. It belongs in logs and traces, never
in a metric label. `merchantId` is borderline — bounded, but check the bound.

**ClickHouse memory.** Do not run the `obs` profile alongside `async` on 32 GB
without checking the budget first.

## Interview payload

**Why you cannot average P99 across instances.**

Percentiles are not linear, so they do not aggregate. If instance A reports P99 =
100 ms and instance B reports P99 = 200 ms, the fleet P99 is **not** 150 ms — it
could be anywhere between 100 and 200 depending on the request distribution
across instances. The mean of percentiles is a number with no meaning.

The correct approach is to export **histogram buckets** from each instance and
merge the buckets server-side, then compute the percentile from the merged
histogram. That is exactly what Micrometer's `percentiles-histogram` does, and
why `publishPercentiles` is the wrong choice for a multi-instance service.

This is a genuinely discriminating question and most candidates get it wrong.

**Be ready for:** *"So what's the cost?"* Buckets are more data on the wire and
in storage, and percentile accuracy is bounded by bucket boundaries. That is the
trade, and it is worth it.
