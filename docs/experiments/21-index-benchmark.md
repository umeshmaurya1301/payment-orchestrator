# 21 — What three indexes were worth

**Phase 8.** `tools/loadtest/index-benchmark.sh`, `V15__payment_indexes.sql`

---

## Hypothesis

Phase 8 asks for a benchmark table with real numbers from real data, a covering
index demonstrated with `Using index`, and **at least one deliberately bad index
measured and explained** — because showing what does not work is more convincing
than a table where everything improved.

The prediction:

1. `merchant_reference` has no index and is nearly unique, so a lookup is a full
   scan and an index should turn it into a point read.
2. A covering index on the dashboard query should show `Using index` and beat the
   existing non-covering one.
3. An index on `currency` — one distinct value — should be useless. The
   optimiser will ignore it.

Prediction 3 is wrong in an interesting way.

## Setup

**131,585 payments, 116,251 attempts, 125,823 idempotency records**, accumulated
by the experiments in this repository. Nothing generated for the benchmark, which
is phase 8's own trap: *"benchmarking on an empty table — every plan is a full
scan and every scan is fast."*

Timings come from `EXPLAIN ANALYZE`, not a stopwatch. Every query here runs in
single-digit milliseconds or less and one `docker exec mysql` costs about fifty,
so shell timing would measure process startup and report every index as
worthless. `EXPLAIN ANALYZE` reports the server's own execution time and its own
row counts — which is exactly what the criterion asks for.

All three indexes ship in `V15__payment_indexes.sql`. None were applied by hand.

## The table

| Query | Index | rows examined | p50 ms | p99 ms |
|---|---|---|---|---|
| **Q1** point lookup by `merchant_reference` | *(none — table scan)* | **131,585** | 24.000 | 27.900 |
| | `ix_payment_merchant_reference` | **1** | **0.015** | **0.021** |
| **Q2** dashboard, busiest hour | `ix_payment_merchant_created` | 49,192 | 46.100 | 49.100 |
| | `ix_payment_merchant_covering` | 49,192 | **9.800** | **11.800** |
| **Q3** `WHERE currency = 'INR'` | `ix_payment_currency` | **131,585** | 21.800 | 24.400 |

## Q1 — the one that pays for itself

*"What happened to my reference"* is the commonest support question there is, and
the column had no index. 123,070 distinct values in 131,585 rows, so it is nearly
unique — close to the best case an index can have.

```
before:  -> Table scan on payment  (cost=13691 rows=132022)
            (actual time=1.63..18.3 rows=131585 loops=1)
after:   1 row examined, 0.015ms
```

**131,585 rows examined to return one, down to one.** 24 ms to 0.015 ms — about
1,600×. This is the uninteresting kind of result: the index does exactly what an
index is for, and the only surprise is that the column did not have one.

Deliberately **not** `UNIQUE`. A merchant's reference is *their* identifier and
this system does not get to decide it is unique — two merchants may both call
something `invoice-1`, and phase 1 already keys idempotency on
`(merchant_id, idempotency_key)` rather than trusting a caller-supplied string to
be globally distinct.

## Q2 — the covering index, and what "covering" means in the plan

```
non-covering   Extra: Using index condition      46.1ms
covering       Extra: Using where; Using index    9.8ms
```

Both examine the same 49,192 rows. The difference is what happens to each one:
`ix_payment_merchant_created` finds the rows and then reads every one from the
clustered index to fetch `state` and `amount_minor`. The covering index already
contains them, so InnoDB never touches the row at all — **4.7× faster for the
same rows**.

The cost is real and worth stating: the covering index duplicates two columns, so
it is wider, and it is written on every insert *and every state transition* — and
a payment transitions up to four times. It earns that here because the columns
are narrow and the query is frequent. "Add a covering index" is not advice, it is
a trade.

## Q3 — the deliberately bad index, and why it is worse than predicted

The prediction was that the optimiser would ignore it. It does not:

```
plan key: ix_payment_currency
rows examined: 131,585        p50: 21.800ms
```

**MySQL chose the index and read the entire table through it.** `currency` has one
distinct value, so the index is a single key entry pointing at every row — it
cannot narrow a search because there is nothing to narrow to. Scanning it is
marginally *cheaper* than scanning the table, because the index is narrower than
the row, so the optimiser picks it and produces 21.8 ms against the table scan's
24.0 ms.

That is the trap in its most convincing form. The plan output names an index. The
timing is a hair faster. Every signal a reviewer looks at says the index is
working — and it is pure cost: maintained on every insert, occupying buffer pool
a useful index could have had, and buying a 9% improvement on a query that should
not be scanning at all.

**The rule it illustrates:** an index is worth its write cost only if it
*eliminates* rows. Selectivity, not usage, is what makes a column worth indexing,
and a column that every query filters on but every row satisfies is the worst
case — because it looks the most useful.

## What surprised me

**The bad index was used, not ignored.** I expected the optimiser to see through
it. Instead it picked it, and the resulting plan looks *better* than the
alternative by every number on screen. "Is the index being used?" is the question
everyone asks in a review, and here the answer is yes and it is still the wrong
index. The question that would have caught it — *how many rows does it eliminate*
— is not one the plan output volunteers.

**The first version of Q2 measured a full scan twice and called it a covering
index.** The query was `merchant_id = X AND created_at >= NOW() - INTERVAL 7 DAY`.
This dataset has **one** merchant and every row falls inside seven days, so that
predicate selects the entire table and no index can help. Both arms reported
131,585 rows and the covering arm was *slower* at p99. The fix is to compute the
busiest hour at runtime and range over that, so the index has something to
exclude.

**And the check passed anyway, on a substring.** The assertion grepped for
`Using index` — which is a substring of `Using index condition`, the thing the
non-covering plan reports. So a query using the *wrong* index in the *wrong* way
satisfied a check written to prove the opposite. Two bugs pointing the same
direction, in a script whose entire job is to be sceptical.

## Standing questions

- **Only one merchant exists in this data.** Every measurement here is
  effectively single-tenant, so `merchant_id` contributes nothing as a leading
  column. On real data it would be the selective part and these numbers would
  shift.
- **`ix_payment_currency` is still in the schema.** Left deliberately so the
  benchmark can keep measuring it, and because deleting the example would make
  the writeup unverifiable. It should not exist in a real deployment and the
  migration says so.
- **No write-cost measurement.** The whole argument against the bad index is that
  it costs inserts, and that cost is asserted rather than measured. An insert
  benchmark with and without the three indexes would close it.
