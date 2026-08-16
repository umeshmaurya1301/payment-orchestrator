# 07 — Dashboards and alerts (phase 4e)

The first experiment in this series whose subject is not the payment system.
Everything from 3a to 3f is unchanged; what is under test is whether the thing
watching it can tell that any of it happened.

> **Headline.** Four alerts were written from the phase plan. **Three of them
> could not have fired**, and each for a different reason that no amount of code
> review would have surfaced.
>
> One was querying a counter that had been registered as a gauge, so its
> windowed `increase` returned 36 over a period in which the value never moved
> off 4 — nine samples summed instead of differenced. One was thresholding on a
> condition the system cannot physically produce, because the bulkhead's Little's
> law ceiling (~133 TPS) sits *below* the egress contract (200 TPS) it was meant
> to protect. And one measured HTTP 5xx at the edge, which stayed at **zero
> through a run where 99.7% of payments failed** — because a declined payment is
> a `201`.
>
> That last one exposed the real finding: six phases of resilience work, twenty-odd
> metrics, and **not one of them counted whether a payment succeeded.** Every
> series in the system was about a mechanism.
>
> The drill that proves the rest also lied twice before it was trusted — once
> because the verifier read the script off stdin and never saw its own input, and
> once because SigNoz applies an undocumented 2-minute `eval_delay` on top of the
> 5-minute window, so "resolved" cannot be observed for about eight minutes.

---

## Hypothesis

Written before the runs.

> Phases 3a–3f built six components and instrumented every one of them, so the
> series a dashboard needs already exist and this should be a wiring exercise:
> point SigNoz at the metrics, draw four panels, write four thresholds.
>
> I expect the interesting failure to be in the thresholds rather than the
> plumbing — specifically that at least one alert will be written against a
> series that cannot cross it, and that the only way to find out is to run
> chaos and watch nothing happen.
>
> **Least confident about:** whether a chaos run that trips one alert trips the
> others. Phase 3 spent six experiments establishing that these layers sit in
> front of one another, and a layer that sheds traffic is a layer that hides the
> one behind it from the evidence.

---

## Setup

| | |
|---|---|
| Observability | SigNoz v0.137.1, external `signoz-network`, ClickHouse capped at 4 GB |
| Metrics | Micrometer → OTLP push, 15 s step, **delta** temporality |
| Traces / logs | already flowing from 4a–4d, unchanged |
| Alert channel | webhook → `payorch-alert-sink`, one JSON line per notification |
| Load | `ramp.js`, 300 rps, via `tools/obs/alert-drill.sh` |
| Provider | `mockpsp`: 200 TPS egress, 20 bulkhead permits, breaker 50% over 20 calls in 30 s |

Metrics were not reaching SigNoz at all before this step. 4a disabled the OTLP
metrics push deliberately — there was no collector then, and each service logged
a stack trace per export attempt. Turning it back on is one property, and it
lives in `payorch-obs.override.yml` rather than in the defaults, because that
file *is* the statement that a collector exists.

---

## A. The alert that could not have fired

The four panels the phase plan asks for are breaker state, per-provider latency,
error rate and bulkhead saturation, and three of those are gauges. Gauges were
fine. The fourth alert — egress limiter saturation — is the one that broke.

"Saturating" is a rate: rejections *per unit time*. Asking SigNoz for the
increase of `payorch.ratelimit.rejected` over a window returned this, against a
counter that had not moved off 4 for the whole window:

```
timeAggregation=increase   →  36
timeAggregation=max        →   4
```

An increase larger than the maximum is impossible for a counter that starts at
zero. 36 is nine samples of 4, summed. SigNoz was not differencing the series
because it had not been told it was a counter — every one of these totals was
registered with `Gauge.builder`:

```java
Gauge.builder("payorch.ratelimit.rejected", limiter, RateLimiter::rejected)
```

That choice was deliberate and, for two phases, correct. `RetryMetrics` even
documents it: these are monotonic in-memory sums, and reading them through a
function is cheaper than mirroring every increment into a second data structure.
Under a Prometheus *scrape* it is invisible, because you compute the delta
yourself from the sample values and a cumulative gauge answers that perfectly.

It stops being invisible the moment the same series is pushed to a system that
dispatches on declared type.

**Declaring the type in SigNoz does not fix it.** `POST /api/v2/metrics/metadata`
with `{"type":"Sum","temporality":"Cumulative","isMonotonic":true}` returns
`success` and changes nothing: the query path reads the `__temporality__` label
written onto each sample at ingest, not the metadata table. Verified by re-running
the same query afterwards and getting the same 36.

The fix is at the emitter. `FunctionCounter` keeps the original reasoning intact —
it still reads a monotonic value through a function, with no mirrored state — and
adds the one thing that was missing, which is the type:

```java
FunctionCounter.builder("payorch.ratelimit.rejected", limiter, RateLimiter::rejected)
```

Confirmed on the wire afterwards:

| metric | type | temporality | monotonic |
|---|---|---|---|
| `payorch.ratelimit.rejected` | **Sum** | **Delta** | true |
| `payorch.bulkhead.rejected` | **Sum** | **Delta** | true |
| `payorch.retry.attempted` | **Sum** | **Delta** | true |
| `payorch.breaker.state` | Gauge | Unspecified | false |
| `payorch.bulkhead.available.total` | Gauge | Unspecified | false |
| `payorch.retry.budget.tokens` | Gauge | Unspecified | false |

The gauges that stayed gauges are the levels: a breaker state, free permits, and
tokens in a bucket that goes down as well as up. Making *those* counters would be
the same mistake pointing the other way.

**What it cost.** Micrometer's Prometheus naming convention appends `_total` to
counters, so `payorch_retry_attempted` is now `payorch_retry_attempted_total`.
Experiments 02 and 03 quote the old names and have been annotated rather than
rewritten — they record what was measured, not a query anyone runs today.

**Why this is worth a section.** Nothing failed. No exception, no warning, no
gap in a graph. A number with the right shape and no meaning was sitting on the
panel that decides whether a limiter is saturating, and the only thing that
surfaced it was trying to write an alert against it and checking the arithmetic
by hand.

---

## B. The alert that measured something that cannot move

The phase plan asks for an "error rate" panel and an error-rate alert. The
obvious implementation is 5xx as a share of requests at the merchant-facing
edge, and that is what was written first.

Measured during phase A of the drill, with the provider failing **90%** of
calls:

```
payments-edge  status="201"  count = 9,323
payments-edge  status="5xx"  count =     0
```

Every single payment a `201`. The alert could not have fired if the provider had
been switched off entirely.

This is not a bug in the edge. **A declined payment is a business outcome, not a
transport error.** The request was well formed, accepted and processed, and the
answer was no. Returning 5xx would tell the merchant's own retry logic the
failure was ours and worth retrying, which for a genuine decline it is not.

The filter was verified capable of matching before this was believed — the same
query with `status >= '200'` returns 100%, so the flat zero is an absence of
errors and not an absence of data. A filter that silently matches nothing is the
quietest way for an alert to be permanently green.

### What that exposed

Going looking for the right series found that **there wasn't one**:

```
$ curl -s localhost:8081/actuator/prometheus | grep -iE "payorch.*(payment|outcome)"
(nothing)
```

Phases 3a–3f built six resilience components and instrumented every one of them
— retries attempted, breaker state, permits free, tokens in the bucket, calls
shed. Twenty-odd series, and **not one of them said whether a payment worked.**
Every metric in this system was about a mechanism.

It survived six phases because it looks covered. There is an HTTP status code,
there are latency histograms, there is a state machine persisted to MySQL — it
simply happens that none of them is a count of outcomes, and nothing goes wrong
until you try to alert on the one thing a business would actually ask for.

So `payorch.payment.outcome{state}` now exists, incremented in
`PaymentPersistence` where the terminal states are written:

| tag | meaning |
|---|---|
| `AUTHORIZED` | charged |
| `FAILED` | demonstrably not charged; the merchant may safely retry |
| `UNKNOWN` | sent, unanswered, nobody knows |

Three series, not a success/failure boolean, because **`UNKNOWN` is excluded
from the failure rate on purpose.** It is not a decline — it is a payment that
may well have been charged, it is the state experiment 01 exists to reduce, and
folding it into an error rate would make the deadline budget's entire
contribution invisible. All three are pre-registered at zero so that "no
failures yet" and "not wired up" do not look identical on a dashboard.

No `paymentId` or `merchantId` tag: the phase-4 trap list is explicit that a
per-payment label is one time series per payment and will take ClickHouse down.
That identity lives in the trace and the log line, which already carry it.

### The two views of the same 90 seconds

Read during phase A of the drill, from the two services, at the same moment:

```
payments-edge          status="201"                    9,323      ← "all fine"
payment-orchestrator   payorch_payment_outcome{FAILED} 2,309
payment-orchestrator   payorch_payment_outcome{AUTHORIZED}  7
payment-orchestrator   payorch_payment_outcome{UNKNOWN}     1
```

**99.7% of payments failed** and the HTTP view of the system was a flat line of
success. That is the entire argument for this metric existing, in one pair of
numbers.

---

## C. The alert that could not fire, for a different reason

The first version of the drill applied one chaos profile — `latencyMs 2000` and
`errorRate 0.6` together, 300 rps — on the assumption that a single bad provider
would trip everything. Measured on `psp-connector` during that run:

```
payorch_bulkhead_permitted_total            824
payorch_bulkhead_rejected_total           2,815     ← 77% shed
payorch_breaker_failure_rate              15.08     ← threshold is 50
payorch_ratelimit_rejected_total{egress}      0
```

The bulkhead shed 77% of calls, and **a bulkhead rejection is not a provider
fault**. So the breaker only ever judged the 824 that got through, sat at 15%,
and stayed closed through a run in which the provider was failing 60% of the
time. The egress limiter saw those same 824 calls — nowhere near the 200 TPS it
exists to protect.

Slow-provider chaos cannot open a breaker in this system. The layer in front
sheds exactly the traffic that would have proved the provider was broken.

This is experiment 06's lesson from the other side. There, widening a limit
handed the constraint to the next layer down; here, a tight limit hides the next
layer down from the evidence. Same coupling, and it means **a drill built on one
fault would have "demonstrated" two alerts and quietly left two unexercised** —
which is the exact state the phase plan warns about, arrived at while believing
the opposite.

So the drill has two phases, each aimed at the layer the other one masks:

| Phase | Chaos | Mechanism | Targets |
|---|---|---|---|
| A | `errorRate 0.9`, no latency | Calls complete fast, the bulkhead does not saturate, the breaker sees real provider faults | breaker-open, payment-failure-rate |
| B | no chaos, bulkhead widened 20 → 300 | Moves the binding constraint onto the egress limiter | egress-limiter-saturation |
| C | `latencyMs 2500`, no errors | Calls succeed but crawl; Little's law caps throughput and the bulkhead saturates | provider-p99-breach |

### Why phase B has to manufacture a misconfiguration

`egress-limiter-saturation` could not fire under *any* load, and the reason took
two attempts to find because there are two layers in the way, not one.

From `MockPspAdapter`'s own documentation:

> the egress limiter sits **inside** the bulkhead so a token is spent if and
> only if a request actually goes out

So the bulkhead binds before the egress limiter. For `mockpsp` the bulkhead's
Little's law ceiling is 20 permits ÷ ~150 ms ≈ **133 TPS**, below the **200 TPS**
contract the egress limiter protects. Widening the bulkhead to 300 removed that
constraint — and egress rejections stayed at exactly zero, with 12,377 calls
permitted and none refused.

Because the real ceiling was three services upstream:

```
payorch_ratelimit_rejected_total{application="payments-edge",layer="merchant"}  20,515
```

The full chain, from the merchant's socket to the provider's:

| layer | limit | binds? |
|---|---|---|
| edge · per-merchant | **50 TPS** | ← always, first |
| edge · per-endpoint | 150 TPS | no |
| connector · bulkhead | ~133 TPS | no |
| connector · egress contract | 200 TPS | **unreachable** |

Every layer is tighter than the one it protects, outermost first. **That is
correct design** — it is defence in depth working exactly as intended — and its
consequence is that the innermost limiter can never be reached through the front
door. Experiment 06 hit the same wall and solved it the same way, by raising the
ingress limits so "3e's per-merchant ceiling was not the binding constraint".

Which reframes the alert. `egress-limiter-saturation` is not a load alarm; under
committed configuration the system physically cannot breach a provider's
contract. It is a **misconfiguration alarm** — it fires when someone widens the
front door without checking the contract behind it, or when a provider lowers
the contract underneath us.

So phase B creates that misconfiguration honestly, with one `UPDATE`, mid-run,
no restart:

```sql
UPDATE psp_config SET egress_tps = 20 WHERE psp_id = 'mockpsp';
```

Offered ~50 TPS against a 20 TPS contract, measured 90 seconds in:

```
payorch_ratelimit_permitted_total{layer="egress"}  14,292
payorch_ratelimit_rejected_total{layer="egress"}    1,803   ← 0 in every prior run
```

It is 3f's dynamic config used as an instrument rather than as the thing under
test, and the drill restores 200 TPS on the way out, including on interrupt.

Worth recording as a near miss: `psp-b`'s bulkhead is 125 permits at 2.5 s
latency, which is *exactly* 50 TPS — *exactly* its egress contract. The two
layers bind simultaneously and the outer one wins every time, so that provider
could never have demonstrated this either.

---

## D. What the drill costs in wall-clock, and why the first run lied

The first complete run reported that nothing had fired or resolved. Two separate
reasons, and both are worth recording because both produce *confident wrong
answers*.

**1. The verifier could not read its own input.** The check was written as:

```bash
docker logs payorch-alert-sink | python - "${expected[@]}" <<'PY'
```

`python -` reads the **script** from stdin, and the heredoc is stdin — so the
piped log never arrived, `sys.stdin` was already at EOF, and the checker
reported "never fired" for four alerts that the line immediately above it had
just printed as delivered. It now reads a file. A verifier that cannot fail
loudly is worse than no verifier, because it converts an unknown into a
confident negative.

**2. The pipeline is slower than it advertises.** From SigNoz's own evaluation
log:

```
eval_window: 300000     the 5m the rule looks back over
eval_delay:  120000     a 2m lag before that window even begins
```

`eval_delay` is not mentioned in the UI. It means a rule evaluating at 16:35 is
judging **16:28–16:33** — so with the last breaching sample at 16:28, the window
containing it was still under evaluation until ~16:35. The drill waited 420 s,
declared `provider-p99-breach` unresolved, and was wrong: it resolved shortly
after, and `breaker-open`'s resolved notification landed at 16:34:43 — **22
seconds after the drill printed its verdict.**

Recovery is now 600 s. The honest statement is that this system cannot
demonstrate a resolution in less than about eight minutes, and any drill shorter
than that reports a false negative.

**3. A dirty baseline.** The first run began with `provider-p99-breach` already
firing, left over from an aborted earlier run still inside the 5 m window. An
alert that was already firing cannot demonstrate it fired *because of* the
drill, so the script now blocks until every rule is inactive and refuses to run
if they do not settle.

**4. Credit for somebody else's resolution.** With the baseline gate in place,
the next run still scored `provider-p99-breach` as "fired and resolved" — on the
strength of a `resolved` notification that arrived at 16:39:42 for an instance
that had started at **16:19:12**, before the drill began. Its actual firing, at
16:40:46, had not resolved at all. Matching notifications by alert *name* cannot
tell two instances apart.

Alertmanager repeats `startsAt` unchanged in the resolved notification, so the
check now pairs on it: a resolution only counts if it belongs to an instance
this drill watched fire. This is the third distinct way the verifier produced a
confident wrong answer, and all three shared a shape — the drill was measuring
its own bookkeeping rather than the system.

The full timing budget, once all of that was corrected:

| stage | delay |
|---|---|
| condition stops matching | chaos off + 5 m window + 2 m `eval_delay` |
| resolved notification delivered | + ~2.5 m (measured: cleared 16:48:10, delivered 16:50:40) |
| **total before a resolution is observable** | **~9.5 minutes** |

---

## E. The drill

`tools/obs/alert-drill.sh`, 24 minutes end to end: a gated clean baseline, three
chaos phases of 3 minutes each, and 15 minutes of recovery.

```
drill window: 16:58:35Z -> 17:22:49Z
  chaos on : 16:58:35Z  (phases A, B, C)
  chaos off: 17:07:41Z

--- notifications delivered during this drill (webhook sink) ---
  firing    breaker-open                 17:02:10
  firing    payment-failure-rate         17:02:13
  firing    provider-p99-breach          17:02:16
  firing    egress-limiter-saturation    17:05:20
  resolved  breaker-open                 17:12:10
  resolved  egress-limiter-saturation    17:15:20
  resolved  payment-failure-rate         17:17:13
  resolved  provider-p99-breach          17:17:16

--- exit criterion ---
  ok  breaker-open                 FIRED        resolved
  ok  provider-p99-breach          FIRED        resolved
  ok  payment-failure-rate         FIRED        resolved
  ok  egress-limiter-saturation    FIRED        resolved

  PASS - every alert fired and resolved
```

Every resolution is paired to a firing of the *same instance* by `startsAt`, so
none of it is credit borrowed from an earlier run. The two `resolved` lines at
17:00:4x that appear in the raw artefact belong to the previous drill's
instances and are correctly **not** counted.

Read the delivery times against the phases and the pipeline's latency shows up
directly in the data: chaos ran from 16:58:35, and the first notification landed
at 17:02:10 — about **3.5 minutes** for a condition to become a page. Resolution
took roughly **9.5 minutes** after the fault cleared. Both are properties of the
watcher, not of the payment system, and neither is visible anywhere in SigNoz's
UI.

The phase-4 line — *"an alert that never fires during a chaos run is not
configured correctly"* — turned out to be the most productive sentence in the
plan. Taken literally it condemned three of the four alerts, and each of those
three was a different class of mistake.

---

## What this cost, in API archaeology

Recorded because the published documentation is wrong or absent on every one of
these, and each cost a round trip to discover — the v2 APIs reject unknown fields
outright, so a wrong guess is a 400 with a one-line message and no field path.

| Thing | What the docs say | What v0.137.1 wants |
|---|---|---|
| Login | `POST /api/v1/login` | `POST /api/v2/sessions/email_password`, **with `orgID`**, token in `accessToken` |
| Alert rule | — | `version` must be `"v5"`, separately from `schemaVersion: "v1"` |
| Alert rule | — | at least one notification channel must exist first |
| Dashboard | `POST /api/v1/dashboards` | deprecated; `POST /api/v2/dashboards` |
| Dashboard | `spec.display.name` | **also** a top-level `name`, an RFC 1123 slug |
| Dashboard | — | `schemaVersion: "v6"`, `layouts[].kind: "Grid"` |

`/api/v1/login` is the worst of them, because the SPA's catch-all answers it
`200` with an HTML page. A script that checks the status code sees success and
then fails to find a token in a web page.

Two more, not SigNoz's fault but recorded because they cost the same time:

**Windows Python reads files as cp1252 by default.** `json.load(open(path))` on
a UTF-8 file turned the em dash in a dashboard title into `â€”`, and the API
accepted it — the dashboard would have been titled that permanently.
`encoding='utf-8'` is not optional here.

**Windows Python's `/tmp` is not MSYS bash's `/tmp`.** Writing a payload with
Python to `/tmp/x.json` and reading it with `curl -d @/tmp/x.json` fails with
`option -d: error encountered when reading a file`, because they resolve to two
different directories. Piping through stdin avoids the whole class.

---

## Standing questions

- Nothing here samples logs yet. That is the remaining phase-4 item, and it is
  deliberately last: sampling before correlation works means debugging the
  pipeline with 1% of the evidence.
- `payorch.breaker.failure.rate` is NaN below `minimumCalls`, and NaN is dropped
  by the OTLP exporter, so the series is *absent* rather than gappy whenever a
  breaker is idle. Alerting on it is therefore impossible, which is why
  `breaker-open` alerts on `state`. Whether absent-is-better-than-zero survives
  contact with phase 5's routing is not yet tested.
