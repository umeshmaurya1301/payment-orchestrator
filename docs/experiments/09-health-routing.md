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

> **Update, phase 5's synthetic probe (section H).** The 6.3-point residue above
> was measured against the *old* half-open behaviour — a capped trickle of real
> customer payments used to test a recovering provider, because routing was the
> only way the breaker's own probes ever got made. A `SyntheticProber` now
> supplies those probes instead, on money nobody spent, and `ProviderHealth`
> gates a half-open breaker to **zero** real traffic rather than capping it at
> 12. Measured twice, reproducibly: real traffic to the degraded provider falls
> to **exactly 0%** within 9–10 seconds and **stays at 0% for the remaining ~80
> seconds** of the fault — the ~12-second oscillation documented in section C is
> gone, not merely damped. What remains is a different, more fundamental limit
> the probe cannot touch (the breaker still needs real traffic to *notice* the
> fault in the first place) and a genuinely new cost the probe introduces (a
> thinner recovery signal that takes longer to overwrite a stale one). Both are
> measured in section H rather than assumed away.

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

## H. The synthetic probe, and what it actually closes

Section D left one standing question: *"a synthetic probe would remove the ~4
points that recovery-probing costs... it would need to be a real authorization
of a trivial amount."* This is that probe, built, and measured against a
same-session control rather than against section D's original numbers — which
turned out to matter.

### Why the original numbers could not be reused as a baseline

The first run of the new code measured a pre-fault success rate of 83.8%,
against section D's 97.5%. That is not the fix failing; it is this project's
database being enormously larger than it was when section D was first
measured — hundreds of thousands of rows from a session's worth of load tests
run against the same MySQL instance since. Comparing today's numbers to a
figure recorded against a much smaller database would have been the exact
mistake experiment 26 made with its own "before" index numbers, in a new
costume.

So the comparison here is a same-session **control**: the pre-probe code
(`ProviderHealth`'s half-open score reverted to the 12-point cap, `SyntheticProber`
disabled) and the treatment, run back to back against the identical warm stack,
same database, same load, same fault — `tools/loadtest/routing-experiment.sh`,
unchanged, `MAX_RATE=40 DEGRADE_AFTER=45 DEGRADE_FOR=90 RECOVER_FOR=45
DEGRADE_MODE=errors`, matching section D's original parameters.

### What the summary numbers say, and why they are not the finding

```
                     control   treatment   treatment-2
BEFORE  overall        94.1%       96.5%        95.4%
DURING  overall        92.9%       91.8%        92.4%
AFTER   overall        96.9%       94.9%        96.6%
```

Read as a single before/after pair, this looks like nothing happened — DURING
is not obviously better, and it moves in both directions across the two
treatment runs. **That reading is wrong, and the reason is where the noise
actually lives.** `psp-b`'s own success rate swung between 74.8% and 92.9%
across these three runs with no change to it at all — the mock simulator's own
probabilistic fault injection on the providers NOT under test dominates a
single 90-second window's overall number far more than anything psp-a
contributes. A single run's OVERALL success is not a reliable before/after
metric here, and asserting one from three runs would be the same mistake
experiment 23 made trusting a single k6 pair before running it twice.

### What is reproducible: the per-provider time series

Real traffic to `psp-a`, as a share of all attempts, in 2–3 second buckets
relative to the fault:

```
CONTROL (the old capped score)
  t+0s   66.9%      t+16s   0.8%      t+31s   2.4%      t+46s   0.0%
  t+3s   56.1%      t+19s   3.0%      t+34s   0.0%      t+49s   0.0%
  t+6s   25.5%      t+22s   0.0%      t+37s   0.0%      t+52s   0.9%
  t+9s    0.0%       t+25s   0.0%      t+40s   0.9%      t+54s   6.0%   <- oscillating
  t+13s   0.0%       t+28s   0.8%      t+43s   4.1%      t+57s.. 0.0% (settles)

TREATMENT (gated score + SyntheticProber), run 1 and run 2
  t+0s   77.3%       t+9s    0.0%
  t+2s   78.1%       t+11s   0.0%
  t+5s   70.1%       ...     0.0%   <- flat zero for the remaining ~80s, both runs
  t+7s   29.2%       t+90s   0.0%
```

**Real traffic to the degraded provider falls to exactly 0% within 9–10 seconds
in both treatment runs and stays there — no recurring blips.** Control shows
the ~12-second oscillation section C already documented: small returns of
traffic (0.8%–6.0%) roughly every 11–12 seconds, each one the capped half-open
score buying a trickle of real payments to test a provider still failing 80% of
its calls. That oscillation is **absent in both treatment runs**, not merely
smaller — the reproducibility across two independent runs is what makes this
the finding rather than a run's noise.

`SyntheticProber`'s own counters confirm the mechanism did the work: **42 probe
calls fired, 25 succeeded**, across the two treatment runs. Those are the calls
that used to be real customer payments.

### The reaction window is a different limit, and the probe cannot touch it

Both control and treatment show the *same* initial spike — real traffic to
psp-a at 60–78% for the first 5–7 seconds of the fault, before dropping. That
window is not half-open leakage; it is the time the breaker needs to
**notice** the fault at all. `psp-a`'s breaker requires `breaker_minimum_calls`
(50) inside a 30-second time window before it can evaluate a failure rate and
open — and nothing, probe or otherwise, can shorten that, because the breaker
cannot know a provider is failing without some real calls going out first. The
synthetic probe only ever fires once a breaker is already `HALF_OPEN`; it has
no opinion about a breaker that has not yet opened. This is a more precise
statement of where the residual cost lives than section D had: it was never
really "the cost of probing", it is "the cost of noticing", and a probe that
fires before there is anything to probe cannot help with that part.

### A cost the probe introduces, found by measuring the recovery window

`psp-a`'s share of traffic in the 45-second AFTER window is smaller in both
treatment runs (6.4%, 6.5%) than in control (28.6%) — despite psp-a being
equally healthy in all three by then (95–98% success wherever it is chosen).
That is not the fix failing either; it is the `RollingOutcomes` window (60
seconds, `ObservabilityAutoConfiguration`) doing arithmetic.

Control's psp-a received a continuous ~12% trickle of *real* traffic
throughout the 90-second fault - on the order of 400+ samples, mostly failures
while the fault was live, but a large and fast-refreshing pool. Treatment's
psp-a received only the probe's samples - 42 across the whole experiment,
because the probe fires far less often than real traffic would and only while
`HALF_OPEN`. A 60-second window with fewer, sparser samples takes longer to
dilute the fault-period failures with fresh good ones, so for a while after the
breaker closes the score is still depressed by stale evidence. With a
45-second AFTER window measured immediately after a 90-second fault, the
60-second rolling window has not yet fully rolled past the fault period at
all.

**The trade the probe makes, stated plainly:** it removes real payments from
being spent *testing* a provider, and it pays for that by making the provider
*look* less trustworthy than it now is for a while after it has actually
recovered - because the evidence proving it recovered is thinner than a flood
of real traffic would have produced. Neither number was assumed; both came
from watching the same drill measure both windows.

### What this closes, and what it leaves open

The synthetic probe closes the oscillation section C measured and the
recovery-probing cost section D attributed to it - both were real, both are
gone in two independent runs. It does not, and structurally cannot, close the
breaker's own formation latency, and it introduces a slower-to-refresh
post-recovery signal that section D had no way to discover, because section D
never separated "no synthetic probe" from "no synthetic probe, therefore
lots of real recovery traffic keeping the window fresh."

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

**That the fix for section D's cost created a new cost section D had no way to
see.** Building the synthetic probe felt like closing a standing question, not
opening one. The oscillation genuinely disappeared, reproducibly, in two runs
— and then the AFTER window showed psp-a recovering to a fifth of the traffic
share it used to reclaim, for a reason that had nothing to do with the
mechanism being wrong: a thinner sample pool takes longer to overwrite in a
60-second rolling window than a thick one does. Section D could not have found
this, because section D's "before" always had real traffic keeping the window
fresh throughout. Removing that traffic is the fix, and it also removes the
thing that was quietly making the window recover fast.

**That comparing against an old baseline would have shown an improvement that
was not real, and comparing against a fresh one showed none was provable in a
single run.** The first version of this measurement compared today's numbers
against section D's original ones and found an 83.8% pre-fault baseline where
97.5% was expected — the database had simply grown. A same-session control
fixed that, and then the *overall success* numbers still would not resolve
cleanly across three runs, because a different provider's own randomised fault
injection dominates a single 90-second window more than the mechanism under
test does. Both problems would have been invisible to a script that printed
one number and stopped, which by this point in the project should not have
been a surprise at all.

---

## Standing questions

- **The post-recovery window needs a longer observation** to say whether the
  thinner-sample-pool cost in section H is transient (the 60-second window
  finishes clearing the fault period and psp-a's share converges back toward
  control's) or persistent under repeated faults. `RECOVER_FOR=120` or more
  would answer it and was not run.
- **The probe could also fire briefly after `CLOSED`**, to refresh the rolling
  window with a few known-good samples immediately on recovery rather than
  waiting for real traffic's own trickle to do it - trading a handful more
  synthetic calls for a faster return to full confidence. Not built; section
  H's finding is what motivates it.
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
