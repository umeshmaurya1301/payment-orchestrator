# 03 — Circuit breaker, per provider per operation (phase 3c)

The third component, measured on its own. 3a's deadline and 3b's retry were in
place and unchanged for both arms.

> **Headline.** Against a provider that is comprehensively down, the breaker cut
> load on it by **94%** — 3,145 requests to 186. But the more valuable result is
> what it did to the payments: **2,701 `UNKNOWN` became 2,697 `FAILED` and 4
> `UNKNOWN`.** An `UNKNOWN` payment is an unresolvable liability that needs a
> status poller; a `FAILED` one is safely retryable by the merchant. The breaker
> converted a pile of liabilities into clean failures.

---

## Hypothesis

Written before the runs.

> I expect the breaker to open quickly under a total outage — 20 calls at 30 rps
> is under a second — and then to cut provider load to almost nothing, with only
> the half-open probes getting through. Roughly one probe batch every 10 s.
>
> The payment-state effect is the one I care about. Every rejected call should
> become `FAILED` rather than `UNKNOWN`, because an open breaker means nothing
> was sent.
>
> **Least confident about:** whether the half-open cycle actually recovers, or
> whether a single unlucky probe re-opens it indefinitely. Five permitted probes
> should prevent that, but I have not seen it.

---

## Setup

| | |
|---|---|
| Fault | `mock-psp-simulator` `errorRate: 1.0` — total outage |
| Load | `soak.js`, 30 rps, 90 s (~2,700 payments) |
| Breaker | 50% faults over a 30 s time window, min 20 calls, 10 s open, 5 half-open probes |
| Variable | the breaker only; deadline and retry unchanged |

`errorRate: 1.0` rather than the 40% used in 3b, because a breaker is a
statement about *sustained* failure. At 40% the correct behaviour is to stay
closed and let 3b's retry absorb it — which is a fine thing to verify but a poor
way to observe an open breaker.

---

## Result

| | Before (no breaker) | **After** |
|---|---|---|
| Payments `UNKNOWN` | **2,701** (100%) | **4** |
| Payments `FAILED` (`circuit_open`) | 0 | **2,697** |
| **Requests reaching the provider** | **3,145** | **186** (−94%) |
| p95 | 83 ms | 66 ms |
| Breaker transitions | — | 1 × `CLOSED→OPEN`, 9 × `OPEN→HALF_OPEN`, 8 × `HALF_OPEN→OPEN` |

Nine half-open probe cycles over 90 seconds, which is the configured 10 s open
window behaving exactly as specified.

The 4 remaining `UNKNOWN` are correct and should not be zero: they are calls that
got through during half-open probing, were genuinely sent, and genuinely got no
usable answer. Those are real unknowns. The other 2,697 never left the building.

### Recovery

The half-open path was the thing I was least sure of, so it was exercised
directly: drive traffic until the breaker opens, remove the fault, wait out the
open window, send a trickle.

```
'mockpsp:authorize' CLOSED    -> OPEN
'mockpsp:authorize' OPEN      -> HALF_OPEN
'mockpsp:authorize' HALF_OPEN -> OPEN        ← probe hit the still-broken provider
'mockpsp:authorize' OPEN      -> HALF_OPEN
'mockpsp:authorize' HALF_OPEN -> OPEN
'mockpsp:authorize' OPEN      -> HALF_OPEN
'mockpsp:authorize' HALF_OPEN -> CLOSED      ← provider recovered
```

and the next payment came back `AUTHORIZED`. No intervention, no restart.

`automaticTransitionFromOpenToHalfOpenEnabled` matters here: without it an open
breaker only discovers it should probe when a call arrives, so a provider that
recovers during a quiet period stays cut off until traffic returns, and the
first request back pays for the discovery. Recovery should not depend on someone
happening to ask.

---

## The decisions worth defending

### Per provider *per operation*

`authorize` and `status` get independent breakers, and the reason is the
recovery path. A provider whose authorization is down may still answer status
queries perfectly — and status is precisely what resolves an `UNKNOWN` payment
into a terminal state.

A single per-provider breaker would open on failing authorizations and then
refuse the status calls that were the only way to find out what happened to the
payments already in flight. The system would lose visibility into its own
exposure at the exact moment that exposure was growing. Phase 8's status poller
depends on this separation already existing.

### Time-based window, not count-based

A count-based window of 100 calls is 0.5 s of history at 200 rps and 50 s at
2 rps. "Recent" would mean something different depending on traffic, and this
system's traffic spans two orders of magnitude between a smoke script and a
ramp. A time window makes *"how has this provider behaved in the last 30
seconds"* mean the same thing at every rate. `minimum-calls: 20` covers the
low-traffic case a time window is weak at.

### 50%, from the baseline rather than by feel

A healthy provider here runs at essentially zero: the phase-2 control saw 100%
success and the 30-minute soak saw one failure in 36,001. Half the calls failing
is unambiguous. The phase-3 notes warn against a threshold near the baseline
error rate under load — a breaker set at 50% when the system routinely runs at
40% errors flaps continuously — and 3b's retry already absorbs transient blips,
so this should only fire on sustained failure.

### No slow-call threshold

Resilience4j can also trip on slow calls, and it is deliberately not configured.
3a already covers it: a call exceeding the deadline is abandoned and surfaces as
a failure this breaker counts. A second, independently-tuned slowness rule would
mean two mechanisms with two thresholds for one condition, and the interesting
failures would land in the gap between them.

### What counts as the provider's fault

The subtlest part, and a separate predicate from 3b's retry classification even
though they overlap. They answer different questions — *is it safe to retry* and
*should this count against the provider* — and they diverge in both directions:

| Failure | Retryable? | Provider's fault? |
|---|---|---|
| Connection refused, 5xx, read timeout | yes | **yes** |
| Deadline abandoned *in flight* | yes | **yes** |
| Deadline expired *before sending* | yes | **no** |
| 429 Too Many Requests | yes | **no** |
| 4xx, business decline | no | no |

The two middle rows are the ones a naive "any exception is a failure" breaker
gets wrong:

- **A deadline that expired before sending is our problem.** We were slow —
  queued, overloaded, out of budget. The provider was never contacted. Counting
  it opens the breaker on a healthy provider exactly when we are struggling,
  removing the one dependency that still worked.
- **A 429 means the provider is healthy and we are rude.** It answered promptly
  to say we are exceeding our rate. From 3e we will be generating those
  ourselves through the egress limiter, so counting them would be self-inflicted.

This is the third distinct use of 3a's `wasStarted` flag: state selection in 3a,
retry classification in 3b, provider attribution here.

---

## What surprised me

**The state result matters more than the load result.** I set out to measure
provider protection and got a 94% reduction, which is what a breaker is
advertised to do. The finding I did not anticipate is the effect on the payment
ledger: 2,701 unresolvable payments became 4. Every `UNKNOWN` is a card that may
have been charged, a row phase 8's poller must chase, and a merchant who cannot
safely retry. The breaker eliminated 99.85% of them by making the failure happen
*before* the request was sent, where its meaning is unambiguous.

Fast failure is usually justified as a latency and load argument. Here the
correctness argument is stronger.

**`BREAKER_THRESHOLD=100` does not disable a breaker.** The first "before" arm
used it, expecting an unreachable threshold, and produced 2,695 `FAILED` payments
— because 100% is exactly the failure rate being injected, so the breaker opened
immediately. The genuinely disabled configuration is an unreachable
`minimum-calls`. An impossible-looking result in the control arm is worth more
scepticism than a plausible one in the treatment arm.

**`payorch_breaker_calls_not_permitted` reads 0** despite thousands of rejected
calls, because resilience4j resets that counter on each state transition and this
breaker transitioned 18 times. The reliable measure of "load the provider did not
receive" is the provider's own request count, not the breaker's. Recorded here
because the metric name promises something it does not deliver under a flapping
breaker.

> **Renamed in phase 4e** to `payorch_breaker_calls_not_permitted_total`: it is
> a counter now rather than a gauge. That does not repair the finding above —
> resilience4j still resets it on every state transition, so it under-reports
> under a flapping breaker whatever its instrument type. See
> [07 — Alerts](07-alerts.md).

---

## The open question this leaves

*Your breaker opened — now what?*

Today the answer is "the payment fails fast and correctly", which is better than
before and still not good enough. The merchant's payment did not happen, and
there may be another provider sitting idle that could have taken it.

That is phase 5, and 3c is built for it: every state change is published as a
Spring application event carrying the breaker name, the previous state and the
new one. Nothing listens yet. Emitting it now makes routing a wiring change
later rather than a redesign, and it is why `CircuitBreakers` registers its
listener on the *registry* rather than on individual instances — a provider added
at runtime in 3f is covered without anyone remembering to subscribe it.

---

## Reproducing

```bash
# before: the breaker made unreachable by minimum-calls, NOT by threshold=100
BREAKER_MIN_CALLS=100000000 docker compose up -d
CHAOS_ERROR_RATE=1.0 RATE=30 DURATION=90s \
  tools/loadtest/run-experiment.sh 3c-01-before-no-breaker soak.js

docker compose up -d
CHAOS_ERROR_RATE=1.0 RATE=30 DURATION=90s \
  tools/loadtest/run-experiment.sh 3c-02-after-breaker soak.js

curl -s localhost:8083/actuator/prometheus | grep '^payorch_breaker'
docker compose logs psp-connector | grep 'circuit breaker'
```

The number that matters is the provider's own request count:

```bash
python tools/loadtest/summarise-metrics.py \
  tools/loadtest/results/3c-02-after-breaker/metrics.csv
```
