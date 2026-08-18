# 15 — Capture, and the balances that were quietly wrong

**Phase 6j.** `tools/loadtest/capture-ledger.sh`

---

## Hypothesis

The saga phase 6 still owes is *capture → ledger → notify*, and none of it could
be built, because **capture did not exist**. The provider simulator had a
`/psp/v1/capture` endpoint from phase 1 and nothing had ever called it: no
connector route, no orchestrator endpoint, no event, and a ledger that posted one
pair of legs on `AUTHORIZED` and had never heard of a capture.

So this unit builds the path and asks what the ledger should say about it. The
prediction:

1. A payment produces **two** events now, and the ledger needs two different
   movements. An authorization is a promise; a capture is a collection.
2. Done properly, the `settlement:clearing` balance becomes a number that did not
   previously exist: **the outstanding authorized-but-uncaptured exposure.** It
   goes negative on authorize and returns to zero on capture.
3. The gap the saga has to close is measurable straight away — capture the
   payment while the ledger consumer is failing, and the provider has taken money
   nothing has recorded.

All three held. The experiment also found something nobody predicted, in code
that had been running since phase 6e.

## Setup

```
tools/loadtest/capture-ledger.sh          # three arms, ~4 minutes
```

| Arm | What it does |
|---|---|
| A | 12 payments authorized and left uncaptured |
| B | the same 12 captured |
| C | one payment captured while the ledger consumer fails every record |

The accounting:

```
AUTHORIZED   merchant     +amount      the merchant is owed this
             clearing     -amount      and we carry the liability

CAPTURED     clearing     +amount      the funds arrive
             network      -amount      from the card network
```

Both pairs sum to zero, so phase 6e's table-wide invariant is untouched.

## Graph

The clearing account through one payment's life, and what each system believes at
each step.

```
                       clearing    merchant    network   |  orchestrator
  ------------------  ---------   ---------   --------   |  ------------
  created                     0           0          0   |  INITIATED
  authorized              -4,200      +4,200         0   |  AUTHORIZED
  captured                     0      +4,200     -4,200  |  CAPTURED
                          ^^^^^
                          back to zero: the liability is discharged as
                          the funds arrive, per payment
```

## Actual result

### A and B. Clearing is the exposure

```
   ARM A   authorized total               50400
           clearing moved by             -50400
           ok  clearing carries the whole exposure       50400
           ok  two legs per authorized payment               2

   ARM B   ok  clearing returns to where it began     -6220200
           ok  the network funded the whole batch        50400
           ok  four legs per captured payment                4
           ok  and they net to zero on clearing              0
```

Twelve authorizations move clearing by exactly −50,400; capturing all twelve
returns it to the value it started at. Per payment, the clearing legs net to
zero once captured and stay at −4,200 while they do not. That is the number the
ledger could not express one commit ago.

A second capture is refused with a **409**, and by the state machine rather than
by an idempotency key. That asymmetry with `POST /v1/payments` is deliberate:
creating a payment twice creates a second charge, so it needs a key; capture
names an existing resource and moves it `AUTHORIZED → CAPTURED`, a transition
`PaymentTransitions` permits exactly once. The safety lives in the state machine,
which is the stronger place for it than in a header the merchant has to remember.

### C. The gap, measured before anything was built to close it

```
   WHAT EACH SYSTEM BELIEVES
   the provider                   captured (it answered 200)
   payment-orchestrator           CAPTURED
   the ledger, capture legs       0
   the ledger, entries            2
   clearing for this payment      -4200

   ok   the orchestrator says the money moved          CAPTURED
   ok   the ledger has no capture legs                 0
   ok   so clearing still carries the hold             -4200
   ok   and the books still balance - wrongly          0
```

The last line is the one worth sitting with. **Double entry does not catch
this.** The books balance perfectly while being wrong about the world, because
the missing pair is missing on both sides. An invariant computed over our own
tables cannot see a disagreement with a third party — which is the whole argument
for reconciliation existing *as well as* double entry, and the reason phase 8 is
not optional.

The ladder heals it when the cause is transient. Disarming the seam and waiting
produced all four legs and clearing back at zero. It took **a minute**, not five
seconds, and that is its own small lesson: by the time the cause was fixed the
record had already failed twice and was waiting out the 1-minute tier. Fixing the
problem quickly does not mean recovering quickly — the message is serving a timer
that was set before you fixed anything.

What the ladder does not cover is a *permanent* failure. At the end of 5 s, 1 m
and 10 m the record lands in the DLQ and nothing reverses the capture. That is
what the saga is for.

### D. The thing nobody was looking for

Arms A and B failed on the first run. Twelve payments captured, and clearing had
moved by 33,600 — eight payments' worth. Both arms were short by the same four.

The entries were all there. So the question was where else a balance could come
from, and the answer was a column:

```sql
SELECT a.account_ref, a.balance_minor AS cached,
       SUM(e.amount_minor) AS entries,
       a.balance_minor - SUM(e.amount_minor) AS drift
FROM ledger_account a LEFT JOIN ledger_entry e ON e.account_id = a.id
GROUP BY a.id;
```

```
   account_ref                                  cached      entries        drift
   merchant:0192abcd-0000-7000-8000-...      4,368,000    6,279,000   -1,911,000
   settlement:card-network                     -42,000      -58,800       16,800
   settlement:clearing                      -4,326,000   -6,220,200    1,894,200
```

**1,911,000 minor units** — ₹19,110 — of drift on one merchant account, after two
days of phase-6 experiments.

The cause is a lost update, and it is the most ordinary bug in this repository.
`LedgerPosting` loaded the account entity and did `balance += amount` in Java.
Under `REPEATABLE READ` the load takes no lock, so two consumer threads posting
to the same account both read `X`, both write their own total, and one posting
vanishes from the cached figure while its entries stay on the table. The ledger
runs **three consumer threads over six partitions**, and every single payment
touches `settlement:clearing` — so the hottest row in the schema is also the one
every posting races on.

The fix is one statement:

```java
@Modifying
@Query("update LedgerAccount a set a.balanceMinor = a.balanceMinor + :delta where a.id = :id")
int applyDelta(UUID id, long delta);
```

The arithmetic happens inside InnoDB's row lock, so concurrent postings serialize
instead of overwriting. It costs contention on the clearing row, which is the
correct trade — a contended balance is a throughput problem and a lost update is
a correctness one — and it is why real ledgers eventually shard the clearing
account or stop keeping a running balance at all.

After the fix, arms A and B pass exactly: `clearing moved by -50400` against an
authorized total of 50,400.

## What surprised me

**Both invariants anyone would check were passing.** `SUM(amount_minor)` over
every entry was zero on every run — phase 6e's convergence assertion, green
throughout. And the *sum of the cached balances* was also zero:
4,368,000 − 42,000 − 4,326,000 = 0. Two independent-looking checks, both green,
every individual account wrong.

The second one is not a coincidence, and working out why is the interesting part.
Both legs of a posting are written by the same transaction, so when a
transaction's balance writes are overwritten, **both** legs are lost together.
The losses arrive in balanced pairs, and the aggregate stays at zero by
construction. The arithmetic confirms it exactly: the merchant lost 1,911,000,
and its counterparties gained 1,894,200 on clearing plus 16,800 on network —
1,911,000 to the unit, split by which pair each loss came from.

So the invariant was not merely failing to notice. It was *structurally incapable*
of noticing, and it would have stayed green forever. The check that finds it is a
different question entirely — not "do the entries balance" but "does the cache
agree with the entries" — and it is one query.

**The bug was found by an experiment measuring something else.** Nothing in phase
6j set out to look at concurrency. The capture arithmetic simply did not add up,
and the only reason that was visible is that capture gave the ledger a second
number to check against the first. Phase 6e had one movement per payment and no
way to be caught out; the discipline of asserting an exact expected total, rather
than "a balance changed", is what turned a silent two-day corruption into a
failing line in a shell script.

**Capture needed no vault access at all, and the type says so.** `PspAdapter.capture`
takes no `DetokenizedCard`. The detokenization boundary that `AuthorizationService`
guards still has exactly one caller after adding a second money-moving operation
to the connector — enforced by a signature rather than by a rule someone has to
remember, and pinned by a test asserting the vault stays untouched.

## Standing questions

- **The saga is still owed.** Arm C measures the gap and phase 6k has to close
  it: a capture that dead-letters needs a compensating reversal at the provider,
  not just a red number on a dashboard.
- **`payorch.ledger.drifted_accounts` has no alert rule.** The gauge exists and
  the drill in experiment 14 has the machinery. Zero is the only healthy value
  and nothing currently pages when it is not.
- **Repair is manual on purpose.** `POST /actuator/ledger` recomputes balances
  from entries. Making it scheduled would hide the bug that made it necessary;
  drift should be an alert, and this should be what somebody runs after reading
  it.
- **A running balance may be the wrong design.** It is a cache, it can drift, and
  keeping it correct costs a lock on the busiest row in the system. Summing the
  entries on demand is slower and cannot be wrong. That trade is worth measuring
  rather than assuming, and it is not measured here.
