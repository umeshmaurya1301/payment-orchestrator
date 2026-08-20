# 16 — The compensating reversal

**Phase 6k.** `tools/loadtest/saga-reversal.sh`

> **Status: NOT YET RUN.**
>
> The implementation is complete and unit-tested (343 tests, 0 failures). The
> experiment below is written — hypotheses, arms and assertions — and has not
> been executed against a live stack, because Docker was unavailable in the
> session that built it. There are no measured numbers in this document and
> none will be added until the script has actually run. Every other write-up in
> this directory reports what happened; this one reports what is expected to,
> and the difference is marked rather than blurred.

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
