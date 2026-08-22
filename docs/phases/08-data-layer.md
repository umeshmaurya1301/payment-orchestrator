# Phase 8 — Data layer depth

| | |
|---|---|
| **Estimate** | ~2 weeks |
| **Depends on** | phases 1, 6 and 7 |
| **Delivers** | a measured index benchmark table, reconciliation, and `UNKNOWN` resolution |

## Goal

Make the data layer fast on purpose, and close the `UNKNOWN` loop.

## Why here

Indexing against a guess is worthless; indexing against your *actual* query set
requires the query set to exist. By now it does — routing lookups, status polls,
idempotency checks, ledger reads, recon scans.

`UNKNOWN` resolution also lands here because it needs the provider `status`
operation (phase 1), its own circuit breaker (phase 3c), and a distributed lock
for the job (phase 7).

## Prerequisites

- Real query patterns from phases 1-7
- Enough data volume to make `EXPLAIN` meaningful — generate it with `soak.js`
- Phase 7's Redis lock for job coordination

## Implementation

### 1. Index design against your actual query set

**`EXPLAIN ANALYZE` before and after, every time.** Record `rows examined`, not
just wall time — wall time on a warm buffer pool lies.

Cover these deliberately:

- **Composite index column ordering.** Equality columns first, then the range
  column. An index on `(status, created_at)` serves `status = ? AND created_at > ?`;
  `(created_at, status)` does not, for the same query.
- **Covering indexes.** If the index contains every column the query needs, InnoDB
  never touches the clustered index. Look for `Using index` in `EXPLAIN`.
- **Partial / prefix indexes.** For long varchars, index a prefix.
- **Why an index on a low-cardinality `status` column alone is useless.** With six
  states over a million rows, each value matches ~166k rows. The optimiser will
  correctly prefer a full scan, and your index is pure write overhead. It only
  becomes useful *composed* with a selective column.

UUIDv7 as `BINARY(16)` (phase 1) pays off here: every secondary index carries a
copy of the PK, so 16 bytes vs 36 changes every index's size and fan-out.

### 2. Mongo indexing — the ESR rule

Compound index field order: **Equality, Sort, Range.**

Equality fields first (they narrow the scan), then sort fields (so the index
supplies the order and Mongo skips an in-memory sort), then range fields last
(a range scan cannot be followed by further equality narrowing within the index).

Getting this backwards produces an index Mongo uses for filtering but not for
sorting, and a `SORT` stage that silently blows the 32 MB memory limit on large
result sets.

**TTL indexes** on raw PSP request/response payloads.

### 3. Mongo transactions in the ledger write path

The replica set from phase 6 is what makes this possible.

### 4. Reconciliation job

Ingest a simulated settlement file → Mongo aggregation pipeline → mismatch report.

Three mismatch classes, and each needs a decided action:

| Class | Meaning |
|---|---|
| In our ledger, not in settlement | We think it succeeded, provider disagrees |
| In settlement, not in our ledger | **The double-charge case** — provider took money we never recorded |
| Amount mismatch | Fees, FX, or partial capture |

The second class is precisely the failure mode phase 5's failover nuance warns
about. Recon is what catches it, which is why the two phases reference each other.

### 5. `UNKNOWN` resolution

Background poller that queries providers' `status` and resolves stuck payments
into terminal states.

- Bounded retries with backoff, and a terminal give-up state
- Its own circuit breaker — a provider whose `authorize` is down may still serve
  `status`, which is exactly why phase 3c made breakers per-operation
- Redis distributed lock so only one instance polls (phase 7)
- **Alert on `UNKNOWN` count and age.** A growing `UNKNOWN` backlog is the single
  most important payment-system health signal

#### 8a, as built

**Five phases of putting payments into a state nothing took them out of.** Phase
3a created `UNKNOWN` because a connector timeout does not mean a payment failed —
correct, and half a design ever since. Experiment 01 alone produced **2,372** of
them. Every one is money that may have left a customer's account with nothing in
this system prepared to say so. The state bought time; this job is what the time
was for.

**The resolution is a read-only lookup, never a retry.** Re-authorizing a payment
whose outcome is unknown is precisely the double charge `UNKNOWN` exists to
prevent. Instead: *"have you ever seen this reference?"*, fanned out to **every**
provider — because phase 5's failover means the payment may not be where this
service last thought it was. `UnknownResolverTest` asserts that `authorize`,
`capture` and `reverse` are never called, so a future refactor that reaches for
one fails here rather than in production.

**The reference is the attempt id**, which is why any of this is possible. Phase
1 decided to use the persisted attempt id as the provider's idempotency key; that
decision is what leaves something to ask about when no `providerRef` ever came
back.

**Silence is not a no — the assertion that prevents an abandoned charge.** Two
providers saying no and one saying nothing is *not* "nobody has it". Failing on
it would abandon a charge the customer can see on their statement. So
`definitivelyAbsent()` requires every provider asked, every one answered, and
every one negative; anything else leaves the payment `UNKNOWN` and asks again
later. This is the assertion that would fail first if somebody simplified it to
a null check.

**`UNRESOLVED` is a state, not a counter.** After `max-attempts` the payment
moves to a distinct state rather than getting a flag, because every dashboard,
alert and report groups by `state` — a value that only exists in a column nobody
groups by is a value nobody has. `UNKNOWN` means "we do not know *yet*, the
poller is working"; `UNRESOLVED` means "we do not know, and we have stopped
asking". One of those is worth waking somebody for at 2am and the other may not
be.

**It is deliberately not terminal**, and the phase's phrase *"terminal give-up
state"* is answered by the poller giving up rather than by the table forbidding
the edge. The outgoing edges stay `AUTHORIZED` and `FAILED`; what stops the loop
is that the poller's query never selects `UNRESOLVED`. A truly terminal state
would mean the one person who telephoned the provider and actually has the answer
is the one the state machine refuses to take it from — the payment stays wrong
forever, in the name of tidiness.

**The backoff cap is what makes the attempt bound mean anything.** Uncapped
doubling from a minute reaches four days by the eighth attempt, so "8 attempts"
would mean "a week" and the payment would be functionally abandoned long before
it was formally given up on. Capped at an hour, eight attempts is a few hours —
roughly how long a provider incident lasts.

**Age, not count, is the alert.** A steady hundred `UNKNOWN`s that resolve within
a minute is a busy system behaving correctly; three that have sat for an hour is a
provider that stopped answering. The count cannot separate those.
`payorch.payments.unknown.oldest_age_seconds` is the page-worthy series. Phase
4e's lesson applies directly: the equivalent mistake here would be alerting on
"the poller threw", which stays at zero while the poller cheerfully asks a dead
provider about the same payment every hour for a week.

**Two loose ends from phase 7 are now tied.** `StatusFanout` (7f) and `RedisLock`
(7h) were both committed with no caller, and both were flagged as such rather
than left to look finished. This is their caller. The lock is a **cost control**
here exactly as its javadoc insists — every action the job takes is idempotent,
and `PaymentTransitions` rejects a second instance's transition rather than
double-counting anything.

**The index column order is the query's, not a guess.**
`idx_payment_unknown_poll (state, next_poll_at)` — equality column then range
column, which is this phase's first implementation note applied to the query that
motivated it. `next_poll_at` is stored rather than computed because the backoff
depends on the attempt count, so a computed filter would have to examine every
candidate row; writing the decision when it is *made* turns that into an indexed
range. And an index on `state` alone would be worthless here for the reason this
phase asks to be able to explain — it only becomes useful composed with something
selective.

**Not yet measured.** The `EXPLAIN ANALYZE` evidence for that index needs volume
and a live MySQL, and belongs with the rest of the benchmark table.

### 6. Flyway migration discipline

**Every schema change is a migration, including the index additions above.**
`ddl-auto: validate` enforces this — an entity without a migration fails startup.

For a large table, adding an index is not free. Note `ALGORITHM=INPLACE` and what
it costs.

## Key decisions

| Decision | Defence |
|---|---|
| Measure `rows examined`, not wall time | Wall time on a warm buffer pool is noise |
| ESR for Mongo compound indexes | Wrong order silently drops index-supported sort |
| TTL on raw payloads | Retention as a mechanism, not a policy document |
| Per-operation breaker on the status poller | `authorize` down ≠ `status` down |
| Alert on `UNKNOWN` age | A growing backlog is the earliest sign of real trouble |

## Exit criteria

- [x] A benchmark table in `docs/experiments/`: query, rows examined before,
      rows examined after, p50/p99 before and after — **real numbers from your
      own data** — [experiment 21](../experiments/21-index-benchmark.md), run
      2026-08-21 against 131,585 payments accumulated by this repository's own
      experiments. `merchant_reference` point lookup: **131,585 rows examined →
      1**, p50 **24.000ms → 0.015ms**. Timings from `EXPLAIN ANALYZE` rather
      than a stopwatch, because one `docker exec mysql` costs ~50ms and would
      have reported every index as worthless
- [x] At least one covering index demonstrated with `Using index` in `EXPLAIN` —
      `ix_payment_merchant_covering`. Same 49,192 rows either way; the
      difference is what happens to each: `Using index condition` (46.1ms, goes
      back to the row for `state` and `amount_minor`) against
      **`Using where; Using index`** (9.8ms, never touches the row). **4.7x for
      the same rows.** Note the two strings overlap — the first version of the
      check grepped for `Using index` and was satisfied by the NON-covering
      plan
- [x] At least one deliberately *bad* index measured and explained —
      `ix_payment_currency`, on a column with **one distinct value**, and the
      result is worse than predicted. The optimiser does **not** ignore it: it
      picks it and reads all 131,585 rows through it at 21.8ms against the table
      scan's 24.0ms, because the index is narrower than the row. So the plan
      names an index, the timing is a hair faster, and every signal a reviewer
      checks says it is working — while it is pure write cost. **Selectivity,
      not usage, is what makes a column worth indexing**, and a column every
      query filters on but every row satisfies is the worst case because it
      looks the most useful
- [x] Recon job produces a mismatch report across all three classes —
      [experiment 22](../experiments/22-reconciliation.md),
      `tools/loadtest/reconciliation.sh`, run 2026-08-21. The defects are
      **planted**, one of each class, so the job is measured on whether it finds
      precisely those rather than on whether it finds something:

      | class | found | planted |
      |---|---|---|
      | `SETTLED_NOT_IN_LEDGER` | **1** | a paymentId this system never issued, 9,900 minor |
      | `AMOUNT_MISMATCH` | **1** | a real payment, amount skewed by 700 |
      | `LEDGER_NOT_SETTLED` | 500 (capped) | everything the 3-line file omits |

      The matched line produced **no mismatch of any kind**, which is the other
      half of the result. 43ms over 28,668 journal entries — fast only because
      `SettlementLine.paymentId` is `@Indexed`; phase 8's trap is that an
      unindexed `$lookup` is a collection scan per input document.

      **The class that matters has the fewest rows.** 500 benign against 1 that
      is money, so a threshold on "total mismatches" would be dominated
      permanently by timing noise — the same shape as 6i's finding that DLQ
      depth is the wrong number and `pending` is the right one
- [x] `UNKNOWN` payments resolve automatically; alert fires when the backlog
      grows — 8a. **Both halves now measured.**
      [Experiment 20](../experiments/20-unknown-resolution.md) (run
      2026-08-21) is the resolution half; the alert half closed here, verified
      against a real backlog rather than a config that merely applied cleanly.

      Enabling the poller against 11,601 real `UNKNOWN` payments — oldest 4.3
      days — resolved **nothing**, 250 polls, 250 inconclusive, every provider
      `silent` while the same reference answered a direct `curl` with HTTP 200.
      Three stacked bugs, none of which produced an error:

      1. `StatusFanout.askEveryone` **discarded `subtask.exception()`**, so the
         class that exists to tell "said no" from "said nothing" was silent about
         its own silence and no log line named a cause.
      2. `MockPspAdapter` deserialized `/psp/v1/references/{ref}` into a flat
         record; that endpoint returns a **list**, deliberately, because one
         reference with two authorizations is a double charge. Jackson threw,
         `RestClient` wrapped it, and the adapter reported
         `ProviderUnavailableException` — a provider answering in 2ms with a 200
         recorded as unreachable.
      3. **`RedisLock` had never been created in any service.** It is
         `@ConditionalOnBean(StringRedisTemplate.class)` inside an
         `@AutoConfiguration` with no ordering, and `@ConditionalOnBean` is a
         registration-order check — so phase 7h's lock did not exist at runtime,
         anywhere, from the day it was written.

      After: 50 resolved per tick, `inconclusive` flat at its pre-fix value,
      backlog 11,601 → 11,201 and falling.

      **The alert.** Two rules — `docker/signoz/alerts/07-unknown-backlog-age.json`
      on `payorch.payments.unknown.oldest_age_seconds` (the gauge
      `UnknownResolverMetrics` already published, waiting for this) and
      `08-unresolved-payments.json` on `payorch.payments.unresolved`, the
      poller's own give-up state. **Age, not count** — a steady hundred
      `UNKNOWN`s resolving within a minute is a healthy system; three sitting
      for an hour is a dead provider, and count cannot tell those apart. The
      3-hour threshold is computed, not guessed: `base-backoff-ms=60000,
      max-backoff-ms=3600000, max-attempts=8` sums to a worst-case ~2.05h for
      one payment resolving entirely through normal backoff, so 3h clears that
      with an hour of margin — a lower threshold would page on the poller
      doing its job correctly.

      **Verified against a live backlog, not a dry-run apply.** This
      session's own accumulated testing had left 25,530 real `UNKNOWN`
      payments, oldest ~17.3 hours — turning the poller on and pointing it at
      SigNoz was enough to watch the whole chain for real: the gauge exported
      `62422` (seconds), the rule transitioned `inactive → firing`, and
      `payorch-alert-sink` logged the delivered notification with the correct
      severity. The first evaluation cycle reported `query result is nil` and
      stayed inactive — not a bug, SigNoz's own `eval_delay` (2 minutes) meant
      the first eval's query window predated the metric's first sample by
      seconds; the next cycle saw it and fired. `unresolved-payments` stayed
      `inactive` throughout, correctly — zero rows were actually in that state,
      and a rule that fired on nothing would be exactly the false alarm this
      phase's own text warns against
- [x] Every index added via a Flyway migration — `V15__payment_indexes.sql`.
      None applied by hand; the first attempt was numbered V12 and collided with
      an existing migration, which Flyway caught at startup by refusing to boot

The "deliberately bad index" row is worth including. Showing what does *not* work
and why is more convincing than a table where everything improved.

## Traps

**Benchmarking on an empty table.** Every plan is a full scan and every scan is
fast. Generate volume first.

**Trusting wall time.** Run each query twice and compare `rows examined`.

**Indexing every column named in a `WHERE`.** Every index is write amplification
on an insert-heavy workload. Payments are insert-heavy.

**Forgetting the index is also a write cost.** Measure insert throughput before
and after, not just read latency.

**Mongo `$lookup` in recon.** It is a nested-loop join. On large collections it
will dominate your pipeline; check whether the match can happen application-side.

**A status poller without a give-up state.** Payments stuck in `UNKNOWN` forever
polled forever is a slow-motion outage. Bounded in 8a at 8 attempts — **and the
backoff needs a cap for the bound to mean anything**, or eight uncapped doublings
is a week.

**Treating a silent provider as a negative answer.** The most dangerous line in a
status poller. Two providers saying no and one saying nothing is not "nobody has
it", and failing the payment on it abandons a charge that may well have gone
through. Require every provider to have *answered* before concluding absence.

**A give-up state that is truly terminal.** The person who eventually rings the
provider and learns what happened is then unable to record it, so the payment
stays wrong permanently. Let the poller give up; do not let the table forbid the
truth.

**Expressing "we stopped trying" as a column rather than a state.** Every alert,
dashboard and report groups by `state`. A flag beside it is invisible to all of
them.

## Interview payload

The benchmark table, with a row where the index made things *worse* and you can
explain why.

**Be ready for:** *"Why is an index on `status` useless?"* Low cardinality — six
values over a million rows means each value matches ~166k, so the optimiser
prefers a scan and you have paid write cost for nothing. It becomes useful only
when composed with a selective leading column, and the column order then decides
whether it can serve a range predicate.
