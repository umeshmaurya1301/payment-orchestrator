# ADR 0006 — A retry budget, not just backoff and jitter

**Status:** accepted (phase 3b) · **Evidence:** [experiment 02](../experiments/02-retry.md)

## Context

Retrying a failed provider call raises success rates. It also raises load on the
thing that is already failing, and the two effects arrive together.

Phase 2's baseline makes the scale concrete: at a 40% injected error rate the
system produced **5,322 unresolved payments in 179 seconds**. A naive
three-attempt retry over that failure rate multiplies offered load against an
already-failing provider by roughly **1.8×** — at exactly the moment it needs
less.

## Options considered

**No retry.** Honest, and it leaves easy wins on the table: experiment 02
measured uncapped retry taking success from **61% to 94%**.

**Exponential backoff with jitter, uncapped attempts.** The standard advice, and
it is what most systems ship. It spreads retries out *in time* and does not bound
*how many there are* — so under a broad outage every caller retries, and offered
load rises with the failure rate. Measured: **54% more load on a failing
provider**. Backoff manages the shape of the herd, not its size.

**Fixed attempt cap per request.** Bounds one request and not the system. A
hundred requests each retrying three times is still three hundred calls.

**A token bucket over total traffic** — every request contributes tokens, every
retry costs one. The ratio is of *total requests*, not of failures, which
matters: a ratio of failures would rise as the failure rate rose, which is
backwards.

## Decision

A retry budget, following gRPC and the Google SRE book. `tokensPerRequest = 0.1`
caps retries at **10% of traffic however bad things get**, and the bucket empties
fastest exactly when failures are most widespread.

## Consequences

- **Success is lower than uncapped retry**: 67% against 94%, for 12% extra load
  against 54%. That is the trade, stated as a number rather than as a
  preference. A system optimising for a single request's success would choose
  differently; one optimising for the provider's recovery chooses this.
- A denial is **not an error** — it is the budget working — so it is a metric
  rather than a log line, and a rising `deniedRetries` means the system is
  protecting a struggling downstream rather than that something is broken.
- The bucket **starts full**, so the first minutes after a deploy behave like
  every other minute. An empty bucket would deny every retry until enough
  traffic had flowed to fill it.
- `maxTokens` caps accumulated credit, because a long quiet period would
  otherwise bank enough tokens to fund an unbounded retry storm the moment
  things break.
- The budget is **shared state on the hot path**, which is a contention point.
  `RetryBudget` uses a short lock around three arithmetic operations rather than
  a CAS loop, and phase 7g measured that `synchronized` no longer pins a carrier
  thread on JDK 25 — so the obvious objection to that choice is two JDK releases
  out of date.
