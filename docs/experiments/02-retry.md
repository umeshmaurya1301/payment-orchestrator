# 02 — Retry: classification, full jitter, budget (phase 3b)

The second resilience component, measured on its own. 3a's deadline budget was
already in place and unchanged; nothing else was added.

> **Headline.** Retrying is easy and the budget is the hard part. Uncapped
> retries took the success rate from 61.4% to **93.9%** — and put **54% more
> load on a provider that was already failing**. The 10% budget takes 67%
> instead, for **12%** extra load. Which is correct depends on why the provider
> is failing, and the honest answer is that this harness cannot tell you.

---

## Hypothesis

Written before the runs.

> With a 40% injected error rate and up to two retries, the naive expectation is
> `1 - 0.4³ = 93.6%` success. I do not expect to see it, because the retry budget
> caps retries at 10% of traffic and 40% of requests are failing — most failures
> will not get a retry at all. I expect something in the high sixties.
>
> I expect the budget to be the interesting part, and I expect to be able to show
> the thing it prevents by removing it.
>
> **Least confident about:** whether the budget's denial count is large enough to
> be convincing, or whether the bucket refills fast enough that it barely bites.

---

## Setup

| | |
|---|---|
| Fault | `mock-psp-simulator` `errorRate: 0.4` — the phase-2 baseline's fault |
| Load | `soak.js`, constant arrival rate, 30 rps, 2 min (~3,600 payments) |
| Retry | max 2 retries, backoff 50–1000 ms **full jitter** |
| Variable | retry configuration only; same build, same images, deadline unchanged |

Three arms: retries off (`RETRY_MAX=0`), retries with the 10% budget, and
retries with the budget effectively removed (`RETRY_BUDGET_RATIO=1000`).

---

## Result

| | No retry | **Budgeted (10%)** | Unbudgeted |
|---|---|---|---|
| Success | 61.4% | **67.2%** | **93.9%** |
| `UNKNOWN` payments | 1,391 | 1,182 | **218** |
| Requests to the provider | 3,695 | 4,153 | 5,685 |
| **Load amplification** | 1.00× | **1.12×** | **1.54×** |
| p95 | 66 ms | 94 ms | 140 ms |
| p99 | 1.3 s | 1.4 s | 174 ms |

The unbudgeted arm landed on 93.9% against a predicted `1 - 0.4³ = 93.6%`. Three
attempts at a 40% independent failure rate is exactly what it should produce, and
that agreement is the best evidence the harness is measuring what it claims to.

### What the budget actually did

```
payorch_retry_attempted              453     ← retries allowed
payorch_retry_budget_denied        1,164     ← the storm that did not happen
payorch_retry_succeeded              261     ← calls that only worked because of a retry
payorch_retry_budget_tokens          0.4     ← bucket empty, actively refusing
payorch_retry_refused_classification   0
payorch_retry_refused_deadline         0
payorch_retry_refused_no_reference     0
```

> **Metric names changed in phase 4e.** Every total above except
> `payorch_retry_budget_tokens` is now a counter rather than a gauge and carries
> a `_total` suffix — `payorch_retry_attempted_total`, and so on. The numbers
> are unchanged; only the instrument type is. The names are left as they were
> measured, because this is a record of a run rather than a live query. See
> [07 — Alerts](07-alerts.md) for what the gauge typing was doing to windowed
> queries, and why finding it required trying to alert on one.

1,617 failures were retryable and the budget permitted 453 of them — 28%. The
bucket sat at 0.4 tokens for most of the run, which is what a working budget
looks like: fully drained, refusing continuously, and refilling only as fast as
traffic allows.

453 retries against 3,601 requests is 12.6% rather than the configured 10%,
because the bucket starts full. That is deliberate — an empty bucket would make
the first minutes after a deploy behave differently from every minute after —
and the overshoot is bounded by `budget-max-tokens` and disappears over a longer
run.

---

## What surprised me

**The budget costs more than I expected.** 67.2% against 93.9% is 26 percentage
points of success rate given up to hold amplification at 1.12× instead of 1.54×.
I had assumed the budgeted arm would land closer to the unbudgeted one; it
doesn't, because at a 40% failure rate the retryable failures vastly outnumber
what 10% of traffic can fund.

That is not an argument against the budget. It is the trade being made visible,
and the number is only defensible if you know what you are buying.

**p99 improved where p95 got worse.** No-retry p99 was 1.3 s; unbudgeted p99 was
174 ms. Retries add latency to the requests they touch — but a request that
would have failed at 1.3 s and been retried by the *client* now succeeds in
174 ms. The tail belongs to whoever retries; doing it in the connector, with
backoff and a budget, is cheaper than leaving it to a caller with neither.

**Nothing was refused for want of an idempotency reference, or by
classification, or by the deadline** — all three counters read zero. That is the
correct result and it is also weak evidence: every injected failure was a 5xx,
and the reference is always supplied by construction. Those gates are covered by
unit tests rather than by this run, and it would be dishonest to present three
zeros as though they demonstrated anything.

---

## The limitation that matters most

**The simulator's error rate does not respond to load.** It fails 40% of calls
whether it receives 3,695 or 5,685 of them.

So the unbudgeted arm's 93.9% is *flattering*, and structurally so. In reality a
provider failing at 40% is usually failing because something about it is
overloaded, and adding 54% more traffic makes it worse — the retries eat the
capacity that would have served the successes, and the measured benefit
evaporates or inverts. That is the retry storm the budget exists to prevent, and
**this experiment cannot show it**, because the instrument has no feedback loop.

What the experiment does establish is the input side: uncapped retries produce
1.54× load under a 40% failure rate, and the budget holds it to 1.12×. Whether
that extra 42% is survivable is a property of the provider, not of this
measurement.

A load-sensitive failure mode in the simulator would close the loop and is worth
building before phase 5's routing work, which will want to compare providers
whose health actually responds to the traffic sent to them.

---

## Classification, which came first

The mechanism is the boring half. The safety argument is the classification, and
it was written before the retry loop existed — the phase-3 plan is emphatic
about that ordering, and it is the ordering that constrains what the loop is
allowed to do rather than the other way round.

| Class | Meaning | Retry |
|---|---|---|
| `SAFE` | Provably never reached the provider — connection refused, DNS failure, 429, deadline expired *before* sending | Freely |
| `RETRY_WITH_SAME_REFERENCE` | May have been processed — read timeout, 5xx, connection reset, deadline abandoned *in flight* | Only with the original reference |
| `NONE` | A definite answer (decline) or a permanent error (4xx) | Never |

**The default is `NONE`.** An unrecognised failure is not retried. The costs are
asymmetric: not retrying something retryable is one avoidable failure, retrying
something that already succeeded is a duplicate charge.

Two details carry most of the safety:

**3a's `wasStarted` flag decides the class.** The same
`DeadlineExceededException` classifies as `SAFE` or as
`RETRY_WITH_SAME_REFERENCE` depending on whether a byte was sent. Building it in
3a was not foresight so much as luck, but it is the reason this sub-step did not
need to guess.

**The reference is a required argument, not a convention.** `Retrier.call` takes
the idempotency reference as a parameter and refuses to retry a
possibly-processed failure without one. Documenting "callers should reuse their
reference" would be a comment, and a comment does not stop the person adding a
second call site next year from generating a fresh id and charging a card twice.
The unsafe call does not compile.

In `psp-connector` the value passed is `command.reference()` — the orchestrator's
attempt id, which is already in the request body and which the provider treats as
its idempotency key. Phase 1 built that; this is what it was for.

---

## A bug the tests caught

`Retrier` originally wrapped checked exceptions in `IllegalStateException` on the
way out. Three tests failed, and they were right twice over:

1. It destroys the type `FailureClassifier` reads, so any outer retry or breaker
   would classify every failure as unrecognised.
2. It breaks callers. `psp-connector` catches `RestClientException` to turn it
   into "the provider did not answer" — a wrapper walks straight past that catch
   into the generic 500 handler. **A payment would go from `UNKNOWN` to an
   unhandled error purely because it passed through a retry.**

Fixed with the sneaky-throw idiom, which preserves the type exactly, at the cost
of a checked exception escaping a method that does not declare it. That is a real
trade-off and it is taken deliberately: `DeadlineExecutor` unwraps
`ExecutionException` for precisely the same reason.

## A test that was wrong

`aRetryThatCannotFitInTheRemainingBudgetIsNotAttempted` failed intermittently. It
configured a 400 ms backoff against a 100 ms budget and asserted no retry — but
**full jitter draws uniformly from `[0, ceiling]`**, so the delay was sometimes
20 ms and fit comfortably. The code was right and the test had assumed equal
jitter.

Rewritten to make the *floor* the deterministic constraint, with a companion
test asserting that a retry which does fit proceeds — otherwise the first test
would pass equally well against a retrier that never retried at all.

---

## Reproducing

```bash
RETRY_MAX=0 docker compose up -d
CHAOS_ERROR_RATE=0.4 RATE=30 DURATION=2m \
  tools/loadtest/run-experiment.sh 3b-01-before-no-retry soak.js

docker compose up -d
CHAOS_ERROR_RATE=0.4 RATE=30 DURATION=2m \
  tools/loadtest/run-experiment.sh 3b-02-after-retry-budgeted soak.js

RETRY_BUDGET_RATIO=1000 docker compose up -d
CHAOS_ERROR_RATE=0.4 RATE=30 DURATION=2m \
  tools/loadtest/run-experiment.sh 3b-03-after-retry-unbudgeted soak.js

curl -s localhost:8083/actuator/prometheus | grep '^payorch_retry'
```

The amplification number — the one the budget is about — comes from the
provider's own request count, not the connector's:

```bash
python tools/loadtest/summarise-metrics.py tools/loadtest/results/<run>/metrics.csv
```
