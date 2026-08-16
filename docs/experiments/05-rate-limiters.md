# 05 — Rate limiters: three layers (phase 3e)

The fifth component, and the one [3d](04-bulkhead.md) asked for by name. 3a's
deadline, 3b's retry, 3c's breaker and 3d's bulkhead were in place and unchanged
for every arm.

> **Headline.** The ingress limiter is the thing that actually saves
> `payments-edge`: **1 `OutOfMemoryError` → 0**, **5,858 transport failures → 0**,
> p99 **22.5 s → 3.2 s**, peak concurrency **3,000 VUs → 47** — and 24% *more*
> payments authorised while doing it.
>
> The fairness experiment found a design error in my own filter. Checking the
> service-wide endpoint limit before the per-merchant one is the obvious
> ordering and it destroys the fairness the layer exists for: a well-behaved
> merchant succeeded **25%** of the time next to a runaway neighbour. Reversing
> the two took the same merchant to **99.89%**.
>
> The Lua earns its complexity at burst boundaries and almost nowhere else.
> Under sustained saturation the non-atomic implementation over-admits by **1%**;
> against a burst it admits **2.2×** the limit with a 64% spread between runs.
>
> The egress limiter cut peak load on the provider from **384.6/s to 55.4/s**
> against a 50 TPS contract — a 7.7× breach, made compliant.

---

## Hypothesis

Written before the runs.

> 3d established that admission control at the PSP call cannot save the edge,
> because the edge's resources are spent before the connector is consulted. So I
> expect the ingress limiter to be the component that finally stops the
> `OutOfMemoryError`, and I expect it to do so without costing throughput —
> everything it sheds was going to fail anyway.
>
> I expect the Lua script to matter. Check-then-decrement over two round trips
> has no lock between them and should over-admit visibly under concurrency.
>
> I expect the egress limiter to be uneventful: it enforces a number we chose,
> against a provider that is not complaining.
>
> **Least confident about:** whether per-merchant fairness actually works. Every
> load profile so far sends as one merchant, so the property has never been
> exercised even once.

The last paragraph was right to be nervous, and for a reason I did not predict.

---

## Setup

| | |
|---|---|
| Store | Redis 7.4, one shared instance, buckets in `rl:*` |
| Merchant limit | 50/s, burst 100 |
| Endpoint limits | `payments.write` 150/s burst 300; `payments.read` 500/s |
| Egress limit | per provider, `mockpsp` |
| Variable | the limiters only; `RATELIMIT_ENABLED=false` is the control |

The control arm keeps the filter in the chain at the same position, doing
everything except deciding — `UnlimitedRateLimiter` admits and counts. Deleting
the beans would have changed the merchant lookup, the filter order and the
response headers along with the variable under test.

A second merchant was seeded (`V4__second_merchant.sql`) because the fairness
question is unanswerable with one.

---

## A. Ingress: the failure 3d could not fix

500 rps, provider slowed to 3 s — the exact conditions of 3d's 500 rps arms, so
the two experiments sit on one axis.

| | Before (no limits) | **After** |
|---|---|---|
| **`payments-edge` `OutOfMemoryError`** | **1** | **0** |
| Container restarts | 1 | **0** |
| **Transport failures** (no HTTP answer) | **5,858** | **0** |
| Dropped iterations | 8,794 | **0** |
| Iterations completed | 21,204 | 29,998 |
| **Payments `AUTHORIZED`** | 678 | **840** (+24%) |
| Payments `FAILED` | 14,668 | 5,481 |
| Rejected (429) | 0 | 23,677 |
| Success rate | 3.2% | 2.8% |
| median | 333 ms | **2 ms** |
| p95 | 17.87 s | **303 ms** |
| p99 | 22.54 s | **3.20 s** |
| max | 26.68 s | **4.49 s** |
| Peak k6 VUs | **3,000** (capped) | **47** |
| `payments-edge` heap | 285 MiB | **101 MiB** |
| Orchestrator Hikari pending | 183 | **0** |
| Requests reaching the provider | 841 | 939 |

The OOM is confirmed in the log, inside the run window:

```
run window          08:00:24 -> 08:02:58
OutOfMemoryError at 08:02:28
```

**Transport failures 5,858 → 0 is the result that matters.** Every one of those
is a merchant who sent a payment and got no answer — not a decline, not an
error, nothing. For a payment API that is the worst possible outcome, because
the caller cannot distinguish "not processed" from "processed, answer lost". The
limiter did not make those requests succeed; it made them get *answered*, with a
429 and a `Retry-After`, in 2 ms.

Peak concurrency tells the same story from inside: 3,000 VUs against 47. The
control arm was carrying thousands of in-flight requests it had already accepted
and could not serve. That is the phase-2 pathology in its final form, and it is
the one thing only admission control can bound.

### The honest column

Success rate went *down*, 3.2% → 2.8%, and that is not a rounding artefact. It
is 3d's finding still in force: the bulkhead's 20 permits against a 3 s provider
cap useful throughput at ~6.7 rps no matter what happens upstream. The rate
limiter did not add capacity and was never going to. What it did was stop the
system destroying itself while failing — which shows up as 24% *more* payments
authorised (678 → 840) despite the lower rate, because the control arm spent its
last thirty seconds dead.

Two components, two different jobs, and neither substitutes for the other.

---

## B. Fairness — where I got the design wrong

One merchant sends 400 rps. Another sends 10 rps and behaves impeccably. The
only number that matters is the polite merchant's success rate. The bulkhead was
opened to 2,000 for these arms so 3d's limit was not the binding constraint —
this experiment is about *who* gets served, not how many.

| | No limits | Endpoint-first | **Merchant-first** |
|---|---|---|---|
| **Polite merchant success** | **0%** | **25.0%** | **99.89%** |
| Polite `AUTHORIZED` | 0 | 224 | **875** |
| Polite throttled (429) | 0 | 672 | **0** |
| Polite failed | 329 | 0 | 1 |
| Polite median latency | **29.55 s** | 1 ms | 3.04 s |
| Polite p95 | **60.00 s** | 3.05 s | 3.67 s |
| Noisy `AUTHORIZED` | 5 | 4,599 | 4,555 |
| Noisy throttled | 0 | 31,094 | 31,034 |
| Dropped iterations | 29,969 | 313 | 422 |
| `payments-edge` restarted | **yes** | no | no |

### Without limits, the polite merchant simply ceases to exist

Zero successes out of 329 attempts. A median latency of 29.55 s and a p95 pinned
at the 60 s ceiling. The merchant did nothing wrong, sent a modest 10 rps, and
received a total outage — caused entirely by a different customer on the same
platform. This is the case for per-merchant limiting, and one run of it is worth
more than any amount of arguing about multi-tenancy.

### With limits, but in the obvious order, it barely improves

My first implementation checked the endpoint limit first. The reasoning in the
javadoc was that the limit protecting the *process* should be the one that
cannot be bypassed, and it sounded right enough that I wrote it down as settled.

It took the polite merchant from 0% to 25%. The limiters were working. The
process was safe. The fairness they were added for did not exist.

**The endpoint bucket is first-come-first-served.** A merchant sending 400 rps
wins that race against one sending 10 rps roughly forty times out of forty-one,
so the shared bucket is drained by the noisy merchant and the polite merchant is
refused *before their own allowance is ever consulted*. The per-merchant limiter
was correct, well-tested, and positioned where it could never fire on behalf of
the person it existed to protect.

Note the shape of this failure: with the endpoint check first the polite
merchant's 429s came back in **1 ms**. The system looked healthy, responsive and
well-governed while delivering a 75% failure rate to a blameless customer.

### Merchant-first

Reversing the two checks took the polite merchant to **99.89% — 875 of 876
requests, zero throttled**, with a median of 3.04 s, which is exactly one call to
a provider running 3 s slow. The noisy merchant was unaffected in aggregate
(4,555 authorised against 4,599), because their throughput was always going to be
their own allowance.

Nothing was given up. Whatever passes the per-merchant check still meets the
endpoint limit, so the process is bounded either way. The cost is one extra Redis
round trip for requests the endpoint gate would have refused, and a merchant
token spent on a request that a platform-wide overload then rejects — which errs
toward being marginally stricter than the contract during an overload, rather
than toward letting one caller consume capacity they were not entitled to.

**The general lesson:** in a chain of admission checks, order by *specificity*.
The narrowest, most attributable limit goes first, so the caller responsible for
an overload is the caller refused. A broad shared limit checked first turns every
narrower limit behind it into decoration.

---

## C. Atomicity — the Lua earns its keep at bursts, not at saturation

### Sustained load, 500 rps for 125 s

Merchant bucket at 50/s with a burst of 100, so the correct total is
50 × 125 + 100 ≈ **6,350**.

| | Admitted | Error |
|---|---|---|
| `atomic-lua` | 6,326 | **−0.4%** |
| `read-modify-write` | 6,392 | +0.7% |

Barely a difference — and that is worth understanding rather than explaining
away. Over-admission needs several callers to read the same **non-zero** token
count before any of them writes back. A bucket held permanently empty by 500 rps
offers almost no such moment: nearly every request finds zero tokens, and both
implementations get that right.

So the naive limiter is at its most accurate exactly where it is under most
pressure, which is the observation that makes it dangerous. "We load-tested it
and it was fine" is the expected result, not a reassuring one.

### One simultaneous burst of 600 against a full bucket

`tools/loadtest/burst.js` fires 600 requests through `http.batch` with no ramp at
all, against a freshly reset bucket. Three runs each:

| | Run 1 | Run 2 | Run 3 | Mean |
|---|---|---|---|---|
| `atomic-lua` | 179 | 166 | 164 | **170** |
| `read-modify-write` | 461 | 364 | 281 | **369** |

The atomic script admits ~170 rather than exactly 100 because the batch takes
1–2 s to drain and the bucket refills at 50/s while it does — `100 + ~1.4 s × 50`.
Correct, and tight: a 9% spread across runs.

The naive one admits **2.2× as many**, and the variance is as damning as the
mean: 281 to 461 is a **64% spread**. The limit is not merely wrong, it is
unpredictable — which means it cannot be sized, cannot be reasoned about, and
cannot be put in a contract. A merchant promised 50 rps might get 100 or 230
depending on how the round trips happened to interleave.

Bursts are not the exotic case. They are deploys, scheduled batches, and retry
storms after an outage — every moment a limit is actually load-bearing.

Two guards keep this from being deployed by accident: it is never the default,
and a service running on it logs a `WARN` at startup naming it as non-atomic and
experiment-only.

---

## D. Egress — the layer that protects the downstream

The bulkhead and ingress limits were opened up so the egress limiter was the
binding constraint, and the contract set to **50 TPS** — the phase plan's
provider-B personality.

| | Before | **After** |
|---|---|---|
| **Peak sustained rate to provider** | **384.6/s** | **55.4/s** |
| Total requests to provider | 16,349 | 5,060 |
| Egress permitted | — | 4,968 |
| Egress rejected | — | 10,561 |
| `psp-connector` heap | 56 MiB | 60 MiB |

Against a 50 TPS contract the unlimited system peaked at **7.7× the agreed
rate** — the kind of breach that gets an account throttled or suspended by the
provider, at which point the outage is theirs to fix and ours to explain.

The 55.4/s in the treated arm is not a violation: the bucket's burst allowance is
50 tokens, and a 5-second measurement window that spans a burst reports slightly
above the sustained rate. The sustained rate never exceeds the contract, which is
what a token bucket guarantees and what a contract means.

Two decisions make this compose rather than interfere:

- **A rate-limited call is not a provider fault.** `ProviderFault` returns false,
  so our own throttling cannot open a circuit breaker against a provider whose
  only offence is having a contract. Counting it would be self-sustaining:
  throttling raises the fault rate, the breaker opens, and the dashboard blames
  the one party that behaved correctly.
- **A rate-limited call is not retried.** `FailureClassifier` returns `NONE`.
  Retrying spends the request's deadline arguing with arithmetic we control, and
  the limit will not have moved by the time the backoff elapses.

The token is spent innermost — inside the bulkhead, immediately before the call —
so it is charged if and only if a request actually goes out. Checking earlier
would spend tokens on calls the bulkhead later refuses, throttling us below the
contract we are trying to fill.

### It never fired in the realistic arms

Worth stating plainly: in every arm where the bulkhead was at its production
setting of 20, the egress limiter recorded **0 rejections** — 3d's concurrency
limit caps throughput at ~6.7 rps, far below any TPS contract worth writing down.
It had to be deliberately isolated to be measured at all.

That is not an argument against it. It is an argument that two constraints exist
and only the tighter one is ever visible, and which one is tighter changes with
provider latency. A provider running healthy at 40 ms and 500 TPS inverts it
immediately: 20 permits admit 500 rps, and the egress limiter becomes the only
thing standing between us and a contract breach.

---

## What this changes

1. **Ingress admission control is the answer to the phase-2 death.** Five
   components in, this is the one that stopped `payments-edge` running out of
   heap, and it did it by refusing work before spending anything on it.
2. **Order admission checks by specificity, narrowest first.** Measured, not
   argued: the obvious order cost a blameless merchant 75% of their traffic while
   every dashboard looked healthy.
3. **Atomicity is a burst property.** A non-atomic limiter passes sustained load
   tests and fails at deploys and retry storms. Test limiters with simultaneity,
   not with throughput.
4. **The bulkhead's static limit is still the system's real ceiling.** Success
   rate did not improve in the ingress arms and could not have. 3f's dynamic
   config is now the blocking item for throughput, exactly as 3d predicted.
5. **Fail-open needs its own metric.** `payorch.ratelimit.store.failures` exists
   because a limiter admitting everything because Redis is down is
   indistinguishable, from a success rate, from a limiter working perfectly under
   light load. That series is the alert.
6. **`payments-edge` metrics went missing again** during the control arm — its
   `/actuator/prometheus` shares the application Hikari pool and starves under
   load. Second experiment in a row where this cost data. Phase-2 task #26 should
   be done before phase 4 points a scraper at it.

---

## Reproducing

```bash
# A. ingress survival
RATELIMIT_ENABLED=false docker compose up -d
CHAOS_LATENCY_MS=3000 MAX_RATE=500 STAGE_DURATION=25s \
  bash tools/loadtest/run-experiment.sh 3e-01-before-no-limits ramp.js

RATELIMIT_ENABLED=true docker compose up -d
CHAOS_LATENCY_MS=3000 MAX_RATE=500 STAGE_DURATION=25s \
  bash tools/loadtest/run-experiment.sh 3e-02-after-limits ramp.js

# B. fairness - bulkhead opened so it is not the binding constraint
RATELIMIT_ENABLED=false BULKHEAD_LIMIT=2000 docker compose up -d
CHAOS_LATENCY_MS=3000 NOISY_RATE=400 POLITE_RATE=10 DURATION=90s \
  bash tools/loadtest/run-experiment.sh 3e-03-fairness-before fairness.js

RATELIMIT_ENABLED=true BULKHEAD_LIMIT=2000 docker compose up -d
CHAOS_LATENCY_MS=3000 NOISY_RATE=400 POLITE_RATE=10 DURATION=90s \
  bash tools/loadtest/run-experiment.sh 3e-05-fairness-merchant-first fairness.js

# C. atomicity - reset the buckets first or the burst starts part-spent
docker exec payorch-redis redis-cli --scan --pattern 'rl:*' \
  | xargs -r docker exec -i payorch-redis redis-cli DEL
docker run --rm --network payorch_payorch -v "$PWD/tools/loadtest:/scripts" \
  -e EDGE=http://payments-edge:8080 -e BURST_SIZE=600 \
  grafana/k6:latest run /scripts/burst.js
# then RATELIMIT_KIND=read-modify-write and repeat

# D. egress - open the bulkhead and ingress so this layer is what binds
RATELIMIT_EGRESS_RPS=100000 BULKHEAD_LIMIT=2000 \
  RATELIMIT_MERCHANT_RPS=2000 RATELIMIT_WRITE_RPS=2000 docker compose up -d
MAX_RATE=500 STAGE_DURATION=25s \
  bash tools/loadtest/run-experiment.sh 3e-07-egress-before ramp.js
# then RATELIMIT_EGRESS_RPS=50 and repeat

# the edge deaths, which no k6 metric reports:
docker compose logs -t payments-edge | grep OutOfMemoryError
```

Restart `payments-edge` between arms. It does not come back healthy on its own
after an OOM, and a run started against a degraded edge measures the degradation.
