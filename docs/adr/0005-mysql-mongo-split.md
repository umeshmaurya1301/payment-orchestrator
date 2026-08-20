# ADR 0005 — MySQL for balances, MongoDB for the journal

**Status:** accepted (phase 6e) · **Evidence:** [experiment 15](../experiments/15-capture-and-drift.md)

## Context

The ledger has two jobs that look like one. It maintains **balances** — small,
hot, read on every posting, and required to be transactionally consistent with
the entries that produced them. And it maintains a **journal** — append-only,
never updated, read by date range during an investigation, and growing without
limit.

## Options considered

**Everything in MySQL.** One store, one transaction, one backup story, one thing
to operate. Genuinely the default answer and the one to beat. Rejected because
the journal is the part that grows without bound and is queried least often, and
putting it in the same instance as the balances means the hottest table in the
system shares its buffer pool with the coldest.

**Everything in MongoDB.** Also viable — the replica set supports transactions
since 4.0. Rejected because the balance invariant is
`SUM(amount_minor) = 0` **checked with a query**, and a relational engine with
real constraints is a better home for something whose correctness is the point.

**Event sourcing: no balances at all, sum the entries on demand.** Removes the
entire class of drift bug by removing the cached value. Rejected on read cost —
every balance read becomes an aggregate over an unbounded table — and noted as
what a real ledger eventually does anyway, usually with periodic snapshots.

**Split: balances in MySQL, journal in Mongo.** Each store does the job its
shape suits.

## Decision

Split, with the ordering **balances first, journal second** on the write path.

## Consequences

- **The two writes are not one transaction and cannot be**, because they are
  different databases — which is the distributed-transaction problem this phase
  spent its length avoiding, reappearing inside the ledger. The ordering is the
  mitigation: a crash between them leaves an entry with no journal line, which
  an operator can reconstruct from the entries. The reverse would leave a
  journal claiming a posting that never happened, which is a lie in the audit
  trail.
- Two stores to operate, two backup lifecycles, two sets of credentials, and a
  replica set required for Mongo transactions that a single node does not
  provide — with an error message that does not obviously say so.
- **The cached balance is a cache, and phase 6j found out what that costs.**
  Read-modify-write on a managed entity lost updates under REPEATABLE READ:
  **1,911,000 minor units of drift** on one merchant account, while
  `SUM(amount_minor)` over every entry was zero on every check and phase 6e's
  convergence assertion passed every run. The books balanced and the balances
  were wrong. The fix is an atomic `balance = balance + :delta`; the lesson is
  that **a denormalised total is a cache, and an uncheckable cache is a rumour**,
  which is why `drift()` exists.
- Every posting now contends on the `settlement:clearing` row, because every
  payment touches it. That is the correct trade — a contended balance is a
  throughput problem and a lost update is a correctness one — and it is the
  reason real ledgers eventually shard the clearing account.
