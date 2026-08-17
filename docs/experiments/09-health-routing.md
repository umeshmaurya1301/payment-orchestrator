# 09 — Health-based routing, failover and strategies (phase 5a–5d)

The phase the whole project is arranged around. Retry, breakers and bulkheads
are on every CV; what almost nobody builds is a system where the breaker's state
is an **input to a decision** rather than a terminal outcome.

> **Headline.** Against a provider degraded to 80% errors mid-run, static
> priority routing kept sending it **100%** of the traffic and end-user success
> collapsed **99.7% → 4.2%**. Health-weighted routing moves the traffic in
> **7 seconds** and holds success at **97.5% → 91.2%**.
>
> That is a 91.5-point improvement and it is **not** the "no spike in error rate"
> the phase plan asks for. The residual 6.3 points are the price of probing a
> broken provider with real payments, they are measured rather than estimated,
> and the section on them is the most useful part of this page.
>
> Two things had to be un-learned along the way. Routing on health *alone* cost
> **5.8 points of steady-state success**, because a score that asks "is this
> provider meeting its own contract" says yes to a provider contracted to be
> mediocre. And a half-open breaker scored generously enough to buy back a sixth
> of the traffic every ten seconds, producing a clean **12-second oscillation**
> visible in the per-interval data.
>
> Failover (5c) then refuses to fire on an ambiguous failure — a hung provider
> leaves the payment `UNKNOWN` with exactly one provider tried — and the drill
> that proves it **failed by passing three times** before it measured anything
> real.

---

## Hypothesis

Written before the runs.

> Phase 3 gave every provider a breaker and phase 4 gave it a rolling P99, so the
> signals exist and this should be an aggregation problem: collapse four numbers
> into one, rank, send traffic to the top.
>
> I expect the shift itself to be easy and the *stability* to be hard — the
> phase plan names oscillation as its own trap, and I expect to need damping.
>
> **Least confident about:** whether the error rate really stays flat. Moving
> traffic off a broken provider takes time to notice, and every payment sent
> during that window is one a merchant lost. "No spike" may turn out to mean "a
> smaller spike".

---

## Setup

| | |
|---|---|
| Providers | `psp-a` 200 ms/0.1% · `psp-c` 800 ms/2% · `psp-b` 2.5 s/4% · `mockpsp` healthy |
| Priority | psp-a(10) → psp-c(20) → psp-b(30), mockpsp demoted to 100 for the run |
| Load | `soak.js`, 40 rps, 180 s |
| Fault | `psp-a` to 80% errors at t+45 s, healed at t+135 s |
| Instrument | `tools/loadtest/routing-experiment.sh`, per-provider attempts every 2 s |

Traffic is steered by `priority` at runtime and restored on exit, so experiments
00–08 still reproduce exactly as written. That is 3f's dynamic config being used
as laboratory equipment.

**Attempts are read from `payment_attempt`, not from metrics.** A retry is two
calls and one routing decision; a call refused by an open breaker is a routing
decision with no call at all. Only the attempt rows answer "where was this
payment sent".

---

## A. The baseline: static priority routing

| window | psp-a share | success |
|---|---|---|
| before the fault | 100% | 99.7% |
| **during** | **100%** | **4.2%** |
| after recovery | 100% | 78.8% |

`psp-c` and `psp-b` — both healthy, both idle — received nothing at all while
95.8% of payments failed. The routing decision was made once, from a column, and
nothing in the system was capable of revising it.

Worth noting the recovery row: 78.8%, not 99.7%. Even after the provider was
healed, the breaker was still cycling and the system took a further window to
come back. Static routing is slow to recover as well as slow to react.

---

## B. Health-weighted routing, and the 5.8 points it cost

The first implementation weighted purely on health score. It shifted the traffic
beautifully and made the system worse in the steady state:

| window | psp-a | psp-c | psp-b | success |
|---|---|---|---|---|
| before | 39.1% | 31.7% | 23.5% | **93.9%** |
| during | 3.9% | 36.4% | 28.1% | 93.7% |

The shift is textbook — psp-a falls to 3.9% and end-user success is *unmoved*.
But the "before" row is the finding: **93.9%, where static routing had 99.7%.**

Nothing was broken. `ProviderHealth` scores each provider against **its own
contract**, which is what makes one number able to rank providers that are
legitimately different — psp-b promises 2.5 s and 4% declines, and delivering
exactly that is psp-b being healthy. So all four scored near 100 and the traffic
split roughly evenly across them.

Health had answered *"is this provider doing what it promised"*. That is the
right question for **rerouting** and the wrong one for **preferring**. Which
provider we actually want — cost, commercial terms, and the plain fact that
psp-a is better — lives in the priority column, and I had thrown it away the
moment a health view became available.

The fix is that the weight carries both: rank decay of ¼ per priority step,
modulated by health squared.

| window | psp-a | psp-c | psp-b | success |
|---|---|---|---|---|
| before | 76.0% | 17.1% | 5.3% | **97.7%** |
| during | 7.6% | 69.0% | 17.7% | 90.2% |
| after | 37.8% | 47.6% | 11.2% | 96.7% |

Steady state recovered to 97.7%. The remaining 2 points against static routing
are the exploration traffic going to genuinely worse providers, and they buy
something specific — see the stale-health note below.

**Degradation still beats seniority**, which is the point: health is squared and
rank decay is linear in the exponent, so a preferred provider falling from 100 to
10 loses a factor of 100 and drops below a neutral provider two ranks beneath it.

---

## C. The oscillation, and tuning against it

The phase plan requires picking a damping strategy deliberately. The choice here
is **proportional weighting rather than hysteresis**, because it removes the
cliff instead of delaying it: best-score-wins is a step function where one point
of difference moves 100% of the traffic, so two providers trade the whole load
back and forth on noise. Weighted is continuous — 10% worse receives
proportionally less — so the system settles instead of ringing.

That handles score noise. It did **not** handle the breaker, and the per-interval
data showed why:

```
 t(s)  psp-a  psp-c  psp-b  mock
   +1   75.8   16.8    6.3    1.1   <- fault injected
   +8    0.0   74.2   17.2    8.6   <- breaker opened, traffic gone
  +17   14.1   64.6   18.2    3.0   <- readmitted
  +29   16.7   59.3   15.7    8.3   <- again
  +41    9.4   65.6   19.8    5.2   <- again
  +53   12.9   66.3   17.8    3.0   <- again
  +57    0.0   74.3   18.8    6.9   <- settles
```

A clean ~12-second cycle, matching the breaker's own 10 s `waitInOpenSeconds`.
Every time it half-opened, the capped score of 30 bought roughly a sixth of the
traffic — while the breaker was only willing to admit **five probe calls**.
Everything past the fifth was refused instantly and became a failed payment. Each
cycle paid real money to re-learn what the previous cycle had already
established.

Lowering the half-open cap from 30 to 12, just above the unroutable floor:

| | psp-a during fault | success during fault |
|---|---|---|
| cap 30 | 7.6% | 90.2% |
| **cap 12** | **5.4%** | **91.2%** |

Better, and not fixed. Which is the honest finding of this experiment.

---

## D. Why the error spike does not go to zero

The exit criterion is "no spike in end-user error rate during the shift". The
measured spike is **97.5% → 91.2%**, and the arithmetic of the residue is:

```
psp-a   5.4% of traffic at 26.6% success   ->  4.0 points of the loss
psp-b  17.6% of traffic at 83.6% success   ->  most of the rest
```

Both terms are deliberate.

**The psp-a term is the cost of automatic recovery.** Routing is where the
breaker's probes come from. A provider starved of *all* traffic never gets a
probe, so its breaker never closes and it never comes back — the stale-health
trap, arrived at from the other direction. Every probe during an outage is a
payment sent to a provider we already believe is broken, and some of them fail.
The trade is: pay ~4 points during the outage, or recover only by hand.

**The psp-b term is the cost of having somewhere to fail over to.** Traffic
displaced from psp-a lands on providers whose contracted decline rates are 2%
and 4% rather than 0.1%. That is not a defect; it is what the alternatives are.
A system with three identical providers would not show it.

So "no spike" is achievable only by giving up automatic recovery, and this
implementation does not. The defensible version of the claim is: **a 91.5-point
improvement, with a measured and explained 6.3-point residue.**

The fix that would get both is a **synthetic probe** — a health call the provider
answers that is not a customer's payment — so recovery is detected without
spending live traffic. That is a real design, it is not built here, and it is the
first item in the standing questions below.

---

## E. Failover, and the three ways the drill lied first (5c)

The phase plan calls this "probably the single highest-value paragraph in the
whole project", and it is the one criterion where being wrong costs a customer
money rather than a merchant a sale.

**The rule: fail over only on errors that prove the request was never processed.
Never on an ambiguous one.**

### Why retry and failover read the same classification differently

Phase 3b already classifies every failure into `SAFE`,
`RETRY_WITH_SAME_REFERENCE` or `NONE`. Failover reuses that classification and
maps it *more strictly*, which is the crux:

| | retry (same provider) | failover (different provider) |
|---|---|---|
| `SAFE` — never sent | yes | **yes** |
| `RETRY_WITH_SAME_REFERENCE` — may have been processed | yes | **no** |
| `NONE` — declined, or permanently broken | no | no |

A retry to the *same* provider is safe whenever the original idempotency
reference is reused: the provider recognises it and returns the original
authorization instead of creating a second. A failover gets none of that
protection, because **an idempotency key is only meaningful inside the namespace
of the provider that issued it.** psp-b has never heard of the reference psp-a
was given and will authorize the card a second time, quite correctly, and you
find out at settlement.

So `FailoverPolicy` is an allowlist of the four codes that prove nothing was
sent — `circuit_open`, `deadline_exceeded`, `rate_limited`, `bulkhead_full` —
defaulting closed. Being wrong in that direction costs one avoidable decline;
being wrong in the other charges a customer twice.

### Two independent controls

`PaymentTransitions` enforces the same rule a second time without knowing about
`FailoverPolicy` at all. An ambiguous failure has already moved the payment to
`UNKNOWN`, and `UNKNOWN`'s row permits only `AUTHORIZED` and `FAILED` — there is
no path back to `ROUTED`. So a double charge needs *both* the policy to
misclassify and the state machine to permit the transition that follows.

That did require a real change to the machine — `AUTHORIZING → ROUTED`, the
failover edge — and a new persistence method, `recordFailedButRoutable`, which
records an attempt's failure **without closing the payment**. `recordDeclined`
moves it to `FAILED`, which is terminal and correctly so: a merchant has been
told the payment failed.

The existing exhaustive transition test caught the change immediately, which is
the table doing its job.

### The result

```
psp-a  UNKNOWN  deadline_abandoned
  ok   providers tried: 1
  ok   payment state:   UNKNOWN
```

The provider hung, the request went out, no answer came back, and the payment
was offered to nobody else.

### The drill lied three times before it passed

Each failure was worth more than the pass, and all three share a shape: a safety
test that reports success because it never actually looked.

**1. Both arms routed to the wrong provider.** The first version degraded psp-a
and asserted on whatever came back. Every payment went to psp-c — phase 5b had
already moved traffic off the degraded provider, so the fault never landed on the
provider under test.

**2. The critical arm inherited the other arm's wreckage.** Running the
unambiguous arm first left psp-a pinned at a half-open score of 12 and therefore
unroutable, so the ambiguous arm silently exercised nothing. Reordered, with a
breaker reset performed through 3f's config path — changing a provider's settings
rebuilds its breaker closed — rather than a test-only hook in production code.

**3. It read the payment id from the response body.** When the provider hangs,
the edge's own 30 s budget expires first and it answers:

```json
{"status":504,"title":"Deadline exceeded","errorCode":"deadline_exceeded",
 "detail":"...may or may not have been created. Retry with the same
           Idempotency-Key rather than a new one."}
```

No payment id. That response is *correct* — it is phase 1's `UNKNOWN` contract
surfacing to the merchant — and it meant the script skipped every payment that
failed to produce an id, which was precisely the set it existed to inspect. It
then reported "no payment was routed to psp-a" while the ambiguous path was
firing on every single call. Payments are now tagged with a unique
`merchantReference` and found in the database.

### And a finding: 5b pre-empts 5c

Arm A never fired, and the reason is not a bug:

```
psp-a breakerState = 1  (open)
  --  no payment was routed to psp-a: its breaker is open, so the router
      scores it 0 and never chooses it.
```

A provider whose breaker is open scores zero, so health routing stops choosing
it *before* a call is ever attempted. Failover-on-`circuit_open` is therefore
largely unreachable through the front door. It still matters for the race — the
breaker can open between the routing decision and the call, and the health view
is up to one poll interval stale — but it is no longer the common path. The
drill reports that rather than claiming a pass it did not earn.

---

## F. Four strategies, per merchant (5d)

The phase plan calls this "the one worth the most and the one most likely to be
skipped". Implementing four strategies is easy; the work is proving they differ.

| strategy | picks | rationale |
|---|---|---|
| `PRIORITY` | first routable by priority | a commercial agreement gives a named provider first refusal |
| `CHEAPEST` | lowest `cost_bps` above the health floor | low-margin merchants |
| `LEAST_LATENCY` | lowest rolling P99 above the floor | merchants where checkout abandonment is the cost |
| `HEALTH_WEIGHTED` | proportional, priority-decayed | the default — see sections B and C |

Selected per merchant by a `routing_strategy` column, read on the payment path,
changeable with one `UPDATE` and no restart. **Every strategy respects the health
floor**: that is not a strategy's choice to make, because "cheapest" with no
floor finds the provider that fails most cheaply, and "priority" with no floor is
section A's baseline.

### The demonstration

`tools/loadtest/strategy-demo.sh`, 25 payments per strategy:

```
PRIORITY          psp-c    100.0%   #########################
CHEAPEST          psp-b    100.0%   #########################
LEAST_LATENCY     mockpsp  100.0%   #########################
HEALTH_WEIGHTED   psp-c     68.0%   #################
                  psp-b     24.0%   ######
                  psp-a      8.0%   ##
```

Four strategies, four different answers.

### Getting there took three corrections, all the same mistake

Each time the router was right and the *harness* was lying — and each is a real
property of deterministic routing rather than a scripting slip.

**1. The seed data didn't separate them.** With the committed priorities,
`mockpsp` is both first in priority *and* the fastest provider, so `PRIORITY`
and `LEAST_LATENCY` both chose it and two of the four strategies looked
identical. Four strategies can only be shown to differ if each has a different
favourite; the demo now sets a priority order where the first provider is
neither the cheapest nor the fastest, and restores it on exit.

**2. `LEAST_LATENCY` picked a 3,000 ms provider over a 10 ms one.** The
`CHEAPEST` block before it had sent 40 sequential payments to psp-b at 2.5 s
each — about 100 seconds — so `mockpsp`'s **60-second rolling window had
expired**. Unmeasured providers sort last by design, so psp-b won by being the
only candidate anyone still had data about.

That is the stale-health trap biting a *strategy* rather than the scorer, and it
is a real property of every deterministic strategy here: **by not exploring, they
starve the alternatives of the traffic that keeps their measurements alive, and
then compare against whatever they already chose.** `HEALTH_WEIGHTED` does not
have this problem, which is most of why it is the default.

**3. The warm-up had the same bug.** The fix for (2) was to warm every provider
before each measured block — using `HEALTH_WEIGHTED`, which concentrates ~76% of
traffic on the top-ranked provider and left the bottom-ranked one with zero
samples. `LEAST_LATENCY` then picked the slowest provider that happened to have
data, for the second time and for the same reason.

The warm-up now enables one provider at a time. Worth naming what that admits:
**it is something the harness can do and production cannot.** There is no way to
make a starved provider prove itself without sending it real payments, which is
exactly the synthetic-probe question left open in section D.

---

## G. The graph

`tools/loadtest/plot-routing.py`, drawn from `payment_attempt` rows. Both charts
plot **the error rate on the same clock as the traffic**, because the phase plan
lists showing only the shift as its own trap: traffic moving while users see
errors is not a success, and a traffic-only chart is the most flattering possible
picture of a system that failed.

ASCII rather than a PNG for the reason `plot-metrics.py` already established —
it stays readable in a diff, in a terminal, and on a machine with no image
viewer, and it cannot silently rot into a broken image link.

**Static priority** — the routing decision made once, from a column:

```
                      v                  ^
  psp-a       ██████████████████████████████████████   avg   93%
  success   ??██▇▇█▇▇█▇▁          ▁ ▁      ▆█▇▇▇█▇██?    47% mean
  before fault  99.7%   during   4.2%   after  78.8%
```

**Health-weighted** — the same fault, the same load:

```
                      v               ^
  mockpsp        ▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁    avg    4%
  psp-a      ▆▆▆▆▆▆▆▅▆▄ ▁  ▁ ▁ ▁          ▁▄▆▆▆▅   avg   29%
  psp-b      ▁▁▁▁▁▁▁▁▁▁▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▁▁▁▁▁   avg   13%
  psp-c      ▂▂▂▂▂▂▂▂▂▃▆▆▆▆▅▆▆▆▆▆▆▆▆▆▆▆▆▆▆▆▃▂▂▁▂   avg   49%
  success   ?▇▇▇▇▇▇▇▇▆▄▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇█?    95% mean
  before fault  95.2%   during  92.2%   after  97.1%
```

Two details worth reading rather than skimming.

**A blank is not a low bar.** Zero share draws a space, not the smallest block,
so "this provider received nothing" is visually distinct from "it received a
trickle" — which is the entire distinction the phase turns on. psp-a's row goes
to blank at the fault and returns as `▁` during the half-open probes.

**The success row is full-scale, 0–100%,** which means the 5-point dip during the
fault is barely visible. That is deliberate: the same scale is what makes the
baseline's collapse to `▁` and the tuned run's flat `▇` directly comparable. A
rescaled axis would make the improvement look larger and the residue smaller, and
the residue is the honest part.

---

## What surprised me

**That routing on health alone made the system worse.** The score was correct,
the routing was correct, and steady-state success fell 5.8 points, because
"healthy" and "preferred" are different questions and I had let one answer both.
The bug was in what I asked, not in what was computed — and it only showed up
because the experiment measured the *undisturbed* state as well as the incident.
An experiment that had only injected the fault would have called this a success.

**That the safety test was the hard part, not the safety rule.** The failover
rule itself is four lines and an allowlist. Getting a drill that could actually
*observe* it took three attempts, and every one of the three failed by passing —
routing away from the fault, inheriting a poisoned starting state, and skipping
the payments it was meant to read. A test that cannot fail is worse than no test,
because it is quoted as evidence.

**That the oscillation was the breaker's period, not the router's.** I expected
to be damping score noise, and built weighted routing for exactly that. The
actual instability came from a component built two phases earlier, ticking at its
own 10-second interval, and it was invisible in aggregate — the "during" row of
the summary shows a stable 7.6%. It only appears when the same data is read at
2-second resolution.

---

## Standing questions

- **A synthetic probe** would remove the ~4 points that recovery-probing costs.
  It also introduces the classic risk that the probe passes while real payments
  fail, so it would need to be a real authorization of a trivial amount, or
  reconciled against live outcomes.
- **Deterministic strategies decay.** `PRIORITY`, `CHEAPEST` and
  `LEAST_LATENCY` stop exploring, so the providers they do not choose go quiet
  and their measurements expire. They therefore react to a degradation later
  than `HEALTH_WEIGHTED`, having less evidence to react to. Measured as a test
  artefact in section F; not yet measured as a production behaviour.
- **Failover on a race.** Arm A showed that `circuit_open` failover is mostly
  pre-empted by health routing. The remaining window is real but narrow, and it
  is not currently measured: how often does a breaker open between the routing
  decision and the call? A counter on that path would answer it.
- **`psp-router` is still a 37-line scaffold.** The decision runs in the
  orchestrator, where it already lived, behind a `HealthWeightedRouter` seam that
  makes moving it mechanical. Splitting it into a service adds a network hop to
  every payment and buys nothing until health must be aggregated across several
  connector instances, which is a phase-10 concern.
