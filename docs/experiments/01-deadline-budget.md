# 01 — Deadline budget (phase 3a)

The first resilience component, measured on its own. Nothing else was added
alongside it: no retry, no breaker, no bulkhead. That is the point of doing
these in order - what follows is attributable to this and only this.

> **Headline.** The improvement is not speed. It is that the system now
> **knows**. Before, 2,112 payments were left in `AUTHORIZING`, a non-terminal
> state no code path will ever resolve. After, every one of the 2,372 payments
> is `UNKNOWN` - the state phase 8's poller exists to resolve - and the caller
> is told, in words, whether it is safe to retry.

---

## Hypothesis

Written before the runs.

> Under a provider that never responds, the current system has no upper bound on
> how long a request lives. I expect a budget to bound it, and I expect the k6
> summary to look *worse* rather than better, because failures that previously
> hid behind the client's own timeout will now be counted as server errors.
>
> The number I actually care about is the state left in the database. A request
> abandoned by the client should leave a payment nobody can account for; a
> request the server gave up on should leave one marked `UNKNOWN`.
>
> **Least confident about:** whether interrupting the worker really aborts the
> HTTP exchange. If the JDK client ignores the interrupt, the caller is released
> on time and the connection leaks anyway - which would be a timeout that
> improves the metrics and nothing else.

---

## Setup

| | |
|---|---|
| Fault | `mock-psp-simulator` `hangRate: 1.0` - never responds |
| Load | `soak.js`, constant arrival rate, 20 rps, 2 m 30 s |
| Variable | **the budget, and nothing else** - same build, same images, same everything |
| Before | `DEADLINE_BUDGET_MS=3600000 DEADLINE_MAX_BUDGET_MS=3600000` (one hour ≈ unbounded) |
| After | defaults: 30 s budget, 60 s ceiling, 50 ms floor |

`hangRate` rather than latency or errors, because it is the fault with no
natural bound: a slow provider eventually answers, a hanging one never does. It
is also the one the phase-2 baseline never ran, so this is its first
measurement.

---

## Result

| | Before (unbounded) | After (30 s budget) |
|---|---|---|
| Server response | **never** | always, at 30.01 s |
| What the client saw | transport failure at k6's own 60 s timeout | `504` with an actionable message |
| p50 / p95 | 59.99 s / 59.99 s | 30.01 s / 30.01 s |
| Requests completed in 150 s | 1,574 | **2,514** |
| Dropped by the generator | 889 | 486 |
| **Payments left `AUTHORIZING`** | **2,112** | **0** |
| **Payments recorded `UNKNOWN`** | 0 | **2,372** |

Both arms had `hangRate: 1.0` verified from a separate process while the run was
in flight, after an earlier attempt produced 1,108 successful payments under a
fault that makes success impossible - an impossible number is the cheapest
contamination detector there is, and it is worth building one into every run.

### The before arm, in one line

```
p50 = 59.99s, p95 = 59.99s, max = 59.99s     ← every request identical
```

That flatness is the tell. Nothing in the system chose 60 seconds; it is k6's
own default request timeout. Every request was terminated by the client, and the
server never formed an opinion about any of them.

### What the two arms left in the database

```
BEFORE                          AFTER
AUTHORIZING   2112              UNKNOWN   2372
```

`AUTHORIZING` is not a terminal state. Those 2,112 payments were mid-transition
when the client walked away, and nothing will ever move them - exactly the
stranded-payment problem the phase-2 soak surfaced, reproduced here on demand
rather than by accident. `UNKNOWN` is a state the system understands: phase 8's
status poller is specified to resolve it by asking the provider what it did with
the reference.

---

## The distinction that matters more than the timing

A deadline can be exceeded two ways, and they are not the same fact about the
world. Both were demonstrated on the live stack.

**Abandoned** - the call went out and was given up on:

```
abandoned 'connector.authorize' after 29963ms; outcome unknown
→ payment UNKNOWN, attempt UNKNOWN / deadline_abandoned
→ 504 "It may or may not have been created. Retry with the same
   Idempotency-Key rather than a new one."
```

**Declined** - there was too little budget left to send anything:

```
declining 'connector.authorize': 0ms remaining, 280ms required
→ payment FAILED, attempt FAILED / deadline_exceeded
→ 504 "The request ran out of time before the payment was created.
   No payment exists."
```

Collapsing these into one "timeout" is the mistake that makes a retry unsafe.
Only the second is a definite non-event; only the second can be retried freely.
`DeadlineExceededException` carries a `wasStarted` flag for exactly this reason,
and `PaymentService` branches on it.

The decline case was produced by setting the floor above what reaches the last
hop (`DEADLINE_BUDGET_MS=300 DEADLINE_MIN_SLICE_MS=280`), which is the phase-3
plan's own example - *a connector receiving a request with 400 ms remaining fails
immediately rather than starting a 3 s call* - made observable.

---

## What surprised me

**The throughput went up.** 2,514 completed requests against 1,574, a 60%
increase, under a fault where the success rate is zero either way. Bounding how
long a doomed request lives freed capacity for the next doomed request to arrive
and be told no. Failing takes resources too, and failing promptly takes fewer of
them.

**The interrupt does work.** This was the thing I was least sure of, and
`DeadlineExecutorTest.abandonedWorkIsInterruptedNotMerelyAbandoned` pins it: the
abandoned task observes its interrupt. On the live stack the platform-thread
count returned to its idle baseline (25-28) after the run rather than climbing.
Had it not worked, this whole sub-step would have been cosmetic -
`CompletableFuture.orTimeout` releases the caller and lets the work run on, which
would have produced better graphs and an identically sick system.

**A measurement correction to the phase-2 baseline.** `jvm_threads_live_threads`
counts **platform** threads only. Virtual threads do not appear in it. The
thread counts in `00-baseline.md` were therefore never a measure of in-flight
work, which is why heap was the metric that moved - the parked requests were
always in the heap and never in that gauge. The baseline's conclusions are
unaffected, since they rested on heap and on `hikaricp_connections_pending`, but
the thread column meant less than it appeared to.

---

## What this does not fix

Being explicit, so 3d is not credited with work done here and vice versa.

A budget bounds how long one request lives. It does **not** bound how many exist
at once. At 20 rps with a 30 s budget the system still carries roughly 600
in-flight requests, and at the 200 rps of the phase-2 baseline it would carry
6,000 - which is what exhausted the heap in the first place. The arithmetic is
unchanged; only the multiplier is now finite.

Admission control is 3d, and the baseline already named it the highest-priority
fix. 3a is what makes it possible to *choose* the multiplier.

Two other things are untouched and belong to their own sub-steps: nothing here
retries anything (3b), and nothing stops the system hammering a provider that is
plainly dead (3c).

---

## A trap worth recording

The first run of the "before" arm produced 30-second responses under a one-hour
budget. Two configuration bugs, both silent:

1. **The YAML block was nested under `server:` instead of `payorch:`** - two
   spaces of indentation, inserted programmatically. The properties record
   defaults every field, so the service started happily on defaults and the only
   symptom was an experiment quietly describing a configuration nobody chose.
2. **`max-budget-ms: 60000` clamped the internal hops.** Raising `budget-ms`
   alone could not disable the deadline, because the ceiling applies to any
   inbound budget - correct behaviour, invisible consequence.

Both are now guarded by one INFO line at startup:

```
deadline budget: 30000ms (max 60000ms, min slice 50ms, inbound header IGNORED)
```

Good defaults make a misplaced configuration indistinguishable from an absent
one. If a component's settings are worth having, the effective values are worth
printing.

---

## Reproducing

```bash
# before: effectively no bound
DEADLINE_BUDGET_MS=3600000 DEADLINE_MAX_BUDGET_MS=3600000 docker compose up -d
CHAOS_HANG_RATE=1.0 RATE=20 DURATION=2m30s \
  tools/loadtest/run-experiment.sh 3a-01-before-no-deadline soak.js

# after: the defaults
docker compose up -d
CHAOS_HANG_RATE=1.0 RATE=20 DURATION=2m30s \
  tools/loadtest/run-experiment.sh 3a-02-after-deadline soak.js

# the decline path, live
DEADLINE_BUDGET_MS=300 DEADLINE_MIN_SLICE_MS=280 docker compose up -d
curl -sX POST localhost:8080/v1/payments -H 'X-Api-Key: pk_test_dev_merchant_key' ...
```

Check the state left behind, which is the actual result:

```sql
SELECT state, COUNT(*) FROM payorch.payment
WHERE created_at > NOW() - INTERVAL 4 MINUTE GROUP BY state;
```
