# 22 — The three mismatch classes

**Phase 8.** `tools/loadtest/reconciliation.sh`, `ReconciliationJob`

---

## Hypothesis

Experiment 15 measured something the ledger cannot fix about itself:

> The books balance perfectly while being wrong about the world, because the
> missing pair is missing on both sides. An invariant computed over our own
> tables cannot see a disagreement with a third party.

Reconciliation is the only thing that can. The phase asks for three classes, each
needing a decided action:

| Class | Meaning |
|---|---|
| In our ledger, not in settlement | We think it succeeded, the provider disagrees |
| In settlement, not in our ledger | **The double-charge case** — money moved that we never recorded |
| Amount mismatch | Fees, FX, or partial capture |

The prediction is simply that a job comparing a settlement file against the
journal finds all three. What is worth measuring is whether it finds *precisely*
the planted ones — a job that reports mismatches everywhere is as useless as one
that reports none.

## Setup

The defects are **planted, not hoped for**. A run against whatever happens to be
in the database produces a number with nothing to compare it to, which proves
only that the job executes. So the batch contains exactly one known defect of
each kind:

| Line | Expected |
|---|---|
| A real posted payment, correct amount | **clean** — no mismatch of any class |
| A payment id this ledger has never issued | `SETTLED_NOT_IN_LEDGER` |
| A real payment with the amount skewed by 700 | `AMOUNT_MISMATCH` |
| *(everything else in the journal)* | `LEDGER_NOT_SETTLED` |

The last needs no planting. A settlement batch of three lines against a journal
of 28,668 leaves almost everything unsettled — which is exactly what that class
looks like in reality on a day the file arrives late, and is why it is the class
that must never be paged on.

```
tools/loadtest/reconciliation.sh
```

## Actual result

```
   ok   SETTLED_NOT_IN_LEDGER - the double charge      1
   ok   AMOUNT_MISMATCH                                1
   ok   LEDGER_NOT_SETTLED                             500
   --   aggregation took                               43ms
```

The double charge, in full:

```json
"SETTLED_NOT_IN_LEDGER": {
  "count": 1,
  "sample": [{
    "paymentId": "00000000-0000-4000-8000-000065929180",
    "providerRef": "sett_00000000",
    "amountMinor": 9900,
    "pspId": "mockpsp"
  }]
}
```

A payment id this system has never issued, 9,900 minor units, present in the
provider's file and absent from the ledger. That is money taken from a
cardholder that no internal check in this project could have found — not the
double-entry invariant, not `drift()`, not the convergence assertion. All three
are computed over our own tables and all three stay green.

**The matched line produced no mismatch of any kind**, which is the other half of
the result: the job discriminates rather than flagging everything.

`LEDGER_NOT_SETTLED` reports 500 because the projection is capped there. That is
deliberate — the class is dominated by ordinary timing, so an unbounded
projection would return most of the journal and bury the two classes that matter
underneath it.

## Why three aggregations rather than one pipeline

A single pipeline producing all three classes is possible. Each class needs a
different join **direction** and a different emptiness test:

- `SETTLED_NOT_IN_LEDGER` must be driven from the settlement side, because that
  is the side holding rows we do not know about. Asking the journal would never
  surface them — you cannot join *from* a row that does not exist.
- `LEDGER_NOT_SETTLED` is driven from the journal, filtered to one batch.
- `AMOUNT_MISMATCH` needs both sides present and then compares a field.

Fusing them costs the ability to explain any one of them to somebody at 3am, and
the query that results is write-only.

## What surprised me

**43 milliseconds, and it should not have been.** Phase 8's trap list says
`$lookup` is a nested-loop join, and an unindexed one is a collection scan *per
input document* — 28,668 scans for this report.

> **Correction, phase 9c.** This section originally concluded: *"It is fast
> because `SettlementLine.paymentId` carries `@Indexed`."* **That index did not
> exist.** Spring Data MongoDB has defaulted `auto-index-creation` to false since
> 3.0, so every `@Indexed` in this project was decoration — asked directly, the
> live database reported one index on each collection, `_id_`, against 47,452
> journal documents.
>
> The measurement was real; the explanation was invented. It is fast because the
> aggregation is driven from the **settlement** side, and a three-line batch means
> three passes rather than 28,668 — the small driving set was doing the work I
> credited to an index.
>
> Measured again in 9c once the indexes were created explicitly: **64ms without
> them, 30–35ms with**. Roughly half, which is worth having and is nothing like
> the difference between a report and an overnight job that the original text
> implied. See [experiment 26](26-mongo-retention.md).

**The class that matters is the one with the fewest rows.** 500
`LEDGER_NOT_SETTLED` against 1 `SETTLED_NOT_IN_LEDGER`, and the 500 are almost
all noise while the 1 is money. A report sorted by count puts the important
finding last, and a threshold alert on "total mismatches" would be dominated
permanently by the benign class. The classes have to be alerted on separately or
not at all — the same shape as phase 6i's finding that DLQ *depth* is the wrong
number and `pending` is the right one.

**Ingest replaces the batch rather than appending to it.** Obvious in hindsight
and worth stating: a settlement file gets re-ingested when somebody re-runs a
failed job, and an append would double every line in it — producing a clean
report, because every line would then match a journal entry twice and none of the
emptiness tests would fire. The failure of a double-ingest is a *quieter* report,
not a noisier one.

## Standing questions

- **The join key is `paymentId`.** A real settlement file carries the provider's
  own reference and a merchant reference, and mapping those back to a payment is
  itself the hard part of ingest. The simulated file carries the payment id so
  this measures reconciliation rather than parsing, and that is a real
  simplification rather than a detail.
- **No decided action per class.** The phase asks for a report *and* an action;
  this delivers the report. `SETTLED_NOT_IN_LEDGER` should probably open an
  incident, `AMOUNT_MISMATCH` should post a fee adjustment, and
  `LEDGER_NOT_SETTLED` should age before it means anything. None of that is built.
- **Nothing schedules it.** Deliberate — a recon that runs at 02:00 whether or
  not the file landed reports a wall of `LEDGER_NOT_SETTLED` that means "the file
  is not here yet", which is how a team learns to ignore the report. Ingest
  should be the trigger, and wiring that needs a real file drop.
