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

- [ ] A benchmark table in `docs/experiments/`: query, rows examined before,
      rows examined after, p50/p99 before and after — **real numbers from your
      own data**
- [ ] At least one covering index demonstrated with `Using index` in `EXPLAIN`
- [ ] At least one deliberately *bad* index measured and explained
- [ ] Recon job produces a mismatch report across all three classes
- [ ] `UNKNOWN` payments resolve automatically; alert fires when the backlog grows
- [ ] Every index added via a Flyway migration

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
polled forever is a slow-motion outage.

## Interview payload

The benchmark table, with a row where the index made things *worse* and you can
explain why.

**Be ready for:** *"Why is an index on `status` useless?"* Low cardinality — six
values over a million rows means each value matches ~166k, so the optimiser
prefers a scan and you have paid write cost for nothing. It becomes useful only
when composed with a selective leading column, and the column order then decides
whether it can serve a range predicate.
