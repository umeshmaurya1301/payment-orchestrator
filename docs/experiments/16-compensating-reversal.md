# 16 — The compensating reversal

**Phase 6k.** `tools/loadtest/saga-reversal.sh`

> **Status: run on 2026-08-20. All three arms pass.** Numbers below are measured.
>
> It took three attempts, and the first two failed for reasons that had nothing
> to do with the saga. Both are recorded in *What surprised me* rather than
> tidied away, because one of them is a defect in this experiment repeating a
> lesson a previous experiment had already written down.

---

## What 6j left open

Experiment 15 arm C measured a gap and stopped there. The provider takes the
money, the ledger cannot post it, and **both** of this system's invariants stay
green while the books are wrong about the world:

- `SUM(amount_minor) = 0` holds, because the missing pair is missing in balanced
  halves.
- `drift = 0` holds, because the cached balances agree with the entries that do
  exist.

Neither check is broken. They are both answering a narrower question than the one
anybody cares about, which is not "do the books balance" but "do the books
describe what happened". Nothing inside the ledger can tell the difference. That
is what a saga is for.

The disagreement is between two services that both did their jobs. The
orchestrator captured, got a 200, and wrote `CAPTURED`. The ledger tried four
times over eleven minutes and could not record it. There was never a moment when
one lock could have covered the provider, the orchestrator's database and the
ledger's database — and the compensation is what not having had one costs.

## The design, in one paragraph

The ledger's `@DltHandler` does not recover. It **requests a compensation** —
publishing to `payment.compensation`, keyed by `paymentId` — and the orchestrator
decides what to do about it. The orchestrator reverses at the provider,
transitions `CAPTURED → REVERSED`, and publishes `payment.reversed` through the
outbox. The ledger consumes that and posts the **inverse of the authorization**,
so all three accounts return to zero for that payment.

No service tells another service what to do. The orchestrator is free to answer
`NOT_CAPTURED` and do nothing, and often will.

## Three things worth arguing about

### 1. The reversal cancels the authorization, not the capture

The obvious reading is wrong, and it is wrong in the way that stays green.

A compensation is raised for a capture the ledger **never posted**, so the
`clearing / card-network` pair does not exist and there is nothing on it to undo.
What does exist is the authorization: the merchant is credited, and we carry the
liability on clearing.

```
AUTHORIZED    merchant      +4200      the merchant is owed this
              clearing      -4200      and we carry the liability

REVERSED      merchant      -4200      the merchant is owed nothing
              clearing      +4200      and we carry none
                                       -> every account back to zero
```

Reversing the *capture* legs instead would credit `settlement:card-network` for
funds it never sent and leave `settlement:clearing` short by the same amount.
`SUM(amount_minor)` would still be exactly zero. That is the **second** time in
two phases that the balanced-books invariant has been structurally unable to see
a real error, and it is why the assertion in arm A is `net_for(payment) = 0`
across all accounts rather than a check on the global sum.

### 2. The guard: `state == CAPTURED && !ledger.hasEntryFor(eventId)`

The second condition is the one that stops the compensation from being worse than
the problem.

A capture can reach the DLQ with its ledger entries **already posted**: the write
succeeds and the *webhook* dispatch then throws four times because a merchant's
endpoint is down. The books are complete; the merchant simply has not been told.
Reversing on that basis would take back money the ledger says is owed, in order
to fix somebody else's HTTP 502 — the compensation becoming the incident.

So the question the handler asks is not "did processing fail" but **"is there
money the ledger cannot account for"**, and only the second one justifies
touching a provider. Arm C is the assertion; without it, arm A's green result
says only "the machinery fires", not "it fires when it should".

### 3. The tombstone, and the operator who helps

`reversed_capture(payment_id)` is written in the same transaction as the reversal
legs, and it exists for one scenario:

> A capture dead-letters. The saga reverses it. The books are correct and the
> payment is finished. **Then somebody replays the DLQ.**

That replay is the feature built in phase 6f, used exactly as intended, by a
person doing the right thing. It has no way to know the event has since been
compensated — and the ledger's own idempotency does not help, because the
`CAPTURED` event genuinely has never been posted. `existsByEventId` is false, the
unique constraint would accept both legs, and every duplicate defence in the
service says go ahead. Clearing gets credited for funds that were given back and
card-network gets debited for a capture that no longer exists, permanently, with
`SUM(amount_minor)` still at zero.

Only the tombstone can see this. Arm B is the difference between a compensation
and a compensation that survives somebody trying to help.


## Actual result

### A. The compensation fires, and the ladder is exactly as long as it says

```
   t             DLQ     comp   failed reversed
   -------- -------- -------- -------- --------
   0s             46        0        0        0
   ...       (flat for ten minutes)
   600s           46        0        0        0
   630s           54        8        0        8
   (settled)
   waiting for the ledger to record the reversals ...... done (60s)

   ok   every payment reached REVERSED                       8
   ok   compensations requested                              8
   ok   compensations that could not be published            0
   ok   compensations correctly skipped                      0
   ok   tombstones written                                   8
   ok   compensations the provider refused                   0
   ok     the capture pair was never posted                  0
   ok     the reversal debited the merchant                  1
   ok     the reversal credited clearing                     1
   ok     four legs in total                                 4
   ok     the payment nets to zero across all accounts       0
   ok   the books still balance                              0
   ok   no account has drifted                               0
```

The DLQ sits flat at 46 for ten minutes and then takes all eight at once. That is
the ladder being real: 5s + 1m + 10m = 665s, and the arrival lands at 630s on a
30-second sample. Nothing about the wait is faked, which is the whole reason this
experiment takes half an hour.

The ledger's own view, straight from the database afterwards:

```
MERCHANT_REVERSAL    8    -33,600
CLEARING_REVERSAL    8    +33,600
imbalance                       0
```

Eight payments of 4,200 minor units each, out and back. Every account returns to
zero for those payments, which is the correct end state for money that left the
cardholder and came back — the books should end up saying nothing happened.

### B. The operator helps, and nothing happens

```
   replayed 8 records from the DLQ
   ok   the replay posted no new legs                        4
   ok   the payment still nets to zero                       0
   ok   still no capture pair                                0
   ok   the payment is still REVERSED                        REVERSED
   ok   no account has drifted                               0
   ok   the replay requested no new compensations            8
```

This is the arm that justifies the tombstone. The replayed capture events have
**never been posted**, so `existsByEventId` is false and the unique constraint
would happily accept both legs. Every idempotency defence the ledger has says go
ahead. Only the tombstone refuses, and `the replay posted no new legs 4` is it
working — four legs before, four after.

The last line is the subtle one: `the replay requested no new compensations 8`
means the counter did not move. A replayed capture that was ignored must not
raise a *second* compensation, or an operator being helpful would reverse an
already-reversed payment.


### C. The failure the compensation could have caused

```
   ok   the capture pair WAS posted                          1
   ok   the guard skipped it                                 1
   ok   no compensation was requested                        0
   ok   the payment is still CAPTURED                        CAPTURED
```

This arm is the one that makes arm A mean something. A capture can reach the DLQ
with its ledger entries **already posted** — the write succeeds and the webhook
then fails four times because a merchant's endpoint is down. The books are
complete; the merchant simply has not been told.

Reversing on that basis would take back money the ledger says is owed, in order
to fix somebody else's HTTP 502 — the compensation causing the incident it exists
to prevent. `state == CAPTURED && !ledger.hasEntryFor(eventId)` is what
distinguishes the two, and the run confirms both halves fire independently:
`skipped` incremented, `requested` did not.

Without this arm, arm A's green result says only *"the machinery fires"*, not
*"it fires when it should"*.

## What surprised me

**Two of the three runs failed, and neither failure was the saga.** That is the
whole story of this page.

**Run 1 — the providers were three separate images.** Every compensation was
refused, and the script said so: `compensations the provider refused: 8`. No
provider had seen one. `mock-psp-a/b/c` build to their own images
(`payorch-mock-psp-a`, `-b`, `-c`), not the simulator's, so rebuilding
`mock-psp-simulator` left the three that routing actually uses on pre-6k code
with no `/psp/v1/reverse`. Every reversal 404'd, the connector turned that into
502 `provider_unavailable`, and the orchestrator's `ConnectorUnavailableException`
dead-lettered all eight compensations.

The diagnostic detail worth keeping: **nothing logged.** The exception was thrown
by `connector.reverse(...)` before reaching the branch that logs "the provider
refused to reverse a capture", so `PaymentService` never said a word and the
orchestrator's log was clean. The only evidence anywhere was the exception FQCN
in the headers of `payment.compensation.dlq`. A counter named "the provider
refused" counted eight refusals from a provider that was never contacted.

**Run 2 — the experiment repeated a lesson experiment 15 had already written
down.** Arm A disarms the chaos seam and asserts on the next line. But the
`payment.reversed` events are published *while the seam is still armed*, so they
fail their first attempt and go onto the ladder like everything else — and are
then serving a 5s, 1m or 10m timer that was set **before** the cause was fixed.
Experiment 15 states this exactly: *"fixing the cause quickly does not mean
recovering quickly — the record is serving a timer that was set before you fixed
anything."* The author of that sentence wrote this script and did not apply it.

Six assertions failed against a saga that was working perfectly. Checking the
database directly showed all eight tombstones, both reversal legs, balanced books
and zero drift — the only thing wrong was **when the question was asked**. The
fix is a `wait_for_reversal_posted` helper, and the run above reports what that
wait actually cost: **60 seconds**, the 1-minute tier.

**I called it a product defect before it was one.** After the first check came
back empty I concluded the ledger never posted reversals, and said so. The check
was simply too early. The evidence arrived four minutes later and said the
opposite. Worth recording because the failure mode is specific to this kind of
system: on a 5s/1m/10m ladder, "I looked and it wasn't there" is not evidence of
absence, and the instinct to escalate from *timing* to *defect* after one look is
exactly wrong.

**Re-running an experiment is a different test from running it.** Two of the
three script bugs only appear on a second run against a non-empty database:
`wait_for_compensation` counted **global** `REVERSED` payments rather than this
run's, so it short-circuited at t=0 against the previous run's eight; and
`reversedCaptures` reads the `reversed_capture` table, so it **survives** the
ledger restart that zeroes the other three compensation counters. Restarting for
a clean baseline zeroes three of four. Every experiment here that has only ever
been run once may carry the same class of bug.

**The 6h guardrail fired, and it was right.** Enabling webhooks for arm C made
the ledger refuse to start: `payorch.webhooks.sign is on and
payorch.webhooks.secret is empty`. That is phase 6h's deliberate fatal-on-startup
rather than a silent downgrade to unsigned, meeting its first real operator —
me — and behaving exactly as its javadoc argued it should.


**Arm C needed the stack broken in a specific way, and the script says so rather
than skipping quietly.** It refuses to run without `WEBHOOKS_ENABLED=true` and a
stopped sink, printing the two commands that produce that state. A guard arm that
silently no-ops is worse than one that does not exist, because the run still
reports PASS.

## The window this does **not** close

Named rather than papered over.

`hasEntryFor` reads MySQL; the compensation request goes to Kafka. They are not
one transaction and cannot be. A DLQ replay that is already in flight when the
guard runs can post the entries the guard just found missing, and the
compensation is then requested for a capture that has since been accounted for.
The orchestrator answers on its own state, where the payment is still `CAPTURED`,
so the reversal **would** go through — leaving the ledger with the capture pair
*and* a reversal.

The window is small (the gap between one read and one send), it requires a human
replaying the DLQ in the same few seconds as the ladder giving up, and closing it
properly needs either a distributed transaction — which this phase exists to
avoid — or a reservation on the payment that both paths contend for, which is
phase 7's problem. It is a known hole with a known shape, which is a different
thing from an unknown one.

## Arms

| | Arm | What it does | What it should show |
|---|---|---|---|
| **A** | `compensate` | Authorize 8 payments cleanly, then arm the ledger seam at **p=1.0** and capture all 8 | Every capture succeeds at the provider; every capture event walks 5s → 1m → 10m → DLQ; every one produces a compensation; every payment ends `REVERSED` with **four legs netting to zero** and **no capture pair** |
| **B** | `replay` | Then `POST /actuator/dlq` — the operator, helping | **Nothing changes.** No new legs, no new compensations, payment still `REVERSED`, no drift |
| **C** | `guard` | A capture whose ledger write **succeeds** and whose webhook fails into the DLQ | `compensation.skipped` increments, `compensation.requested` does **not**, payment stays `CAPTURED` |

`p=1.0` rather than 0.3 for experiment 11's reason: at 0.3 a message reaches the
DLQ 0.81% of the time, so a batch this size would produce no compensations at all
and the arm would pass by measuring nothing.

Arm C is skipped with an explanatory message unless `WEBHOOKS_ENABLED=true` and
the sink is stopped — a skip that says why, rather than an arm that quietly
does not run.

## Preflight refuses to run without

- `EVENTS_PUBLISHER=outbox` on the orchestrator
- `COMPENSATION_ENABLED=true` on the orchestrator — **the new one.** With it off,
  the ledger still publishes compensation requests and nothing consumes them.
  That looks identical to a saga that does not work, and the run would report a
  broken system rather than a disabled one.
- `payment.compensation` and `payment.compensation.dlq` existing at RF=3
- `/actuator/ledger` answering with a `compensation` block — a ledger on a
  pre-6k build would otherwise fail every assertion for the wrong reason
- Zero pre-existing drift

## What the counters mean

Two of them are zero for different reasons, and conflating them would hide a
failure:

| Series | Healthy value | Meaning |
|---|---|---|
| `payorch.ledger.compensation_requested` | rises with incidents | Captures the ledger asked to have reversed |
| `payorch.ledger.compensation_failed` | **0** | The request never left the ledger. Money captured, unaccounted for, and nothing anywhere asking for it back |
| records in `payment.compensation.dlq` | **0** | The request arrived and the **provider refused**. An unresolved disagreement about real money, sitting where a person will find it |
| `payorch.ledger.compensation_skipped` | **not** 0 | The guard declining to reverse a capture whose books are already correct. Each one is a provider reversal that correctly did not happen |
