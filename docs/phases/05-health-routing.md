# Phase 5 — Health-driven routing

| | |
|---|---|
| **Estimate** | ~2 weeks |
| **Depends on** | phases 3 and 4 |
| **Delivers** | the traffic-shift graph — the centrepiece of the README |

## Goal

Under sustained load, degrade PSP-A. Traffic shifts to B and C within N seconds
**without** a spike in end-user error rate.

## Why here

**This is the differentiator. Everything before it was table stakes.**

Retry, breakers and bulkheads are on every mid-level CV. What almost nobody has
built is a system where the breaker's state is an *input to a decision* rather
than a terminal outcome. That requires phase 3's state-change events and phase
4's rolling P99 to already exist and be trustworthy — which is why it sits here
and not earlier.

## Prerequisites

- Phase 3: three providers with distinct personalities, breaker publishing state changes
- Phase 4: rolling per-provider P99 available as a **component**, not a chart
- `psp-router` skeleton from phase 0

## Implementation

### 1. `psp-router` as its own service

Already scaffolded and healthy. Still REST in this phase; gRPC is phase 9.

### 2. Health signal aggregation

Per provider, combine:

| Signal | Source |
|---|---|
| Rolling success rate | phase 4 metrics |
| Rolling P99 | phase 4 sliding window |
| Circuit-breaker state | phase 3c events |
| Bulkhead saturation | phase 3d |

Collapse these into a health score, and be deliberate about the weighting. A
provider that is slow but succeeding is a different problem from one that is
fast but failing, and they should not produce the same score.

Decide the window length explicitly. Too short and routing oscillates on noise;
too long and it reacts after the outage is over.

### 3. Routing strategies, config-selectable per merchant

- **Weighted round-robin** — the baseline
- **Least-latency** — routes on rolling P99
- **Cost-optimised** — cheapest provider meeting a health floor
- **Priority with fallback cascade**

Per-merchant selection is what makes this a product feature rather than a global
switch, and `psp_config` (phase 1) plus dynamic reload (phase 3f) already
support it.

### 4. Fallback

PSP-A fails → attempt on PSP-B. **Read the nuance section before implementing
this.**

## The nuance to be ready for

**Retrying an `authorize` on a *different* provider is dangerous — you can
double-charge.**

If PSP-A actually authorized the payment but the response was lost, failing over
to PSP-B authorizes it a second time. The customer is charged twice and you find
out at settlement.

It is only safe with:

1. **Provider-side idempotency keys** — so a retry to the *same* provider is
   safe, and
2. **Reconciliation** (phase 8) — to catch the case where the first provider
   succeeded but the response never arrived, and
3. **`UNKNOWN` as a real state** (phase 1) — so a timed-out authorize is not
   treated as a failure eligible for failover in the first place.

The defensible rule: **fail over on errors that prove the request was not
processed** (connection refused, breaker open before the call, egress limiter
rejection) and **never on ambiguous ones** (timeout, 5xx after the request was
sent). Ambiguous outcomes go to `UNKNOWN` and are resolved by the status poller.

Being able to articulate this unprompted signals real payments experience. It is
probably the single highest-value paragraph in the whole project.

## Key decisions

| Decision | Defence |
|---|---|
| Breaker state as a routing input | Fail-fast is a terminal outcome; routing weight is a graceful one |
| Health score from four signals | Slow-but-working and fast-but-failing are different problems |
| Per-merchant strategy | Merchants have different cost/reliability trade-offs |
| Failover only on unambiguous failures | Ambiguity plus failover equals double-charge |
| Window length chosen explicitly | Too short oscillates, too long reacts late |

## Exit criteria

- [x] Under sustained k6 load, degrade PSP-A in the simulator —
      `tools/loadtest/routing-experiment.sh` injects the fault partway through a
      run already at steady state, so the clock starts at the fault
- [x] Traffic shifts to B and C within N seconds (state your N, and measure it) —
      **N = 10 s; measured 7 s** for psp-a to fall to half its pre-fault share.
      Measured against its *own* prior share, not an absolute 50%: weighted
      routing leaves the primary on ~76%, so an absolute threshold reported
      "shifted in 0s" before the fault was even injected
- [ ] **No spike in end-user error rate during the shift** — **partially.**
      99.7%→4.2% became 97.5%→91.2%: a 91.5-point improvement with a measured
      6.3-point residue. ~4 points is the cost of probing a broken provider with
      real payments, which is what makes automatic recovery possible at all; the
      rest is that the providers failed over to are contractually worse. Closing
      it needs a synthetic probe — see
      [experiment 09](../experiments/09-health-routing.md)
- [ ] Graph it — this graph goes at the top of the README
- [ ] All four strategies selectable per merchant and demonstrably different
- [x] Failover refuses to fire on an ambiguous timeout, and the payment lands in
      `UNKNOWN` instead — `tools/loadtest/failover-safety.sh`, proven on a live
      stack: a hung provider yields `psp-a UNKNOWN deadline_abandoned` with
      **one** provider tried. Enforced twice over — `FailoverPolicy` is an
      allowlist of the four codes that prove nothing was sent, and
      `PaymentTransitions` independently forbids `UNKNOWN → ROUTED`, so both
      would have to fail together. Note that failover on `circuit_open` is
      largely *pre-empted* by 5b: an open breaker scores 0, so the router stops
      choosing that provider before a call is attempted

The second-to-last criterion is the one worth the most and the one most likely to
be skipped.

## Traps

**Routing oscillation.** Provider A degrades, traffic moves to B, B saturates, traffic
moves back. Damping, hysteresis, or a minimum dwell time — pick one deliberately
and be able to say why.

**The thundering herd on recovery.** When A's breaker half-opens, do not send it
full traffic. Ramp it.

**Health scores that go stale.** If a provider receives no traffic, it generates
no signal, so its health never improves and it never gets traffic again. You need
either a probe or a decay toward neutral.

**Measuring the shift but not the error rate.** The graph that matters shows
*both* — traffic moving and errors staying flat. Traffic moving while users see
errors is not a success.

**Testing failover with a clean failure.** Real failures are ambiguous. Test with
`hangRate`, not just `errorRate`.

## Interview payload

> "My circuit breaker isn't just a fail-fast switch — its state is an input to a
> routing decision. The breaker opens, the routing weight drops, and traffic
> drains gracefully instead of failing."

Then the double-charge nuance above, unprompted.

**Be ready for:** *"How fast does it shift, and how did you choose that?"* The
answer comes from the window length and damping decisions, and should reference
the measured oscillation you tuned against.
