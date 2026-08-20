# ADR 0001 — Transactional outbox over two-phase commit

**Status:** accepted (phase 6a–6d) · **Evidence:** [experiment 10](../experiments/10-outbox.md)

## Context

A payment is written to MySQL and an event announcing it is published to Kafka.
Both must happen or neither, and they are two different systems.

The naive form — commit the row, then publish — has a window. Phase 6a measured
it rather than reasoning about it: a **30-second broker outage cost 20 of 60
payments their events, permanently**. Every one of those payments was
`AUTHORIZED`, every one returned `201` to the merchant, and nothing anywhere
recorded that the ledger would never hear about them. The failure is invisible
from every side.

## Options considered

**XA / two-phase commit across MySQL and Kafka.** The textbook answer, and it
does solve the problem as stated. Rejected on operational grounds: Kafka is a
poor XA participant, a prepared transaction that outlives its coordinator blocks
resources in both systems until a human intervenes, and the failure modes are
worse than the one being fixed. It also requires an XA transaction manager in a
service that otherwise has no need of one.

**Publish first, then commit.** Trades lost events for phantom events — the
ledger hears about a payment the database does not have. Strictly worse: a
missing event can be reconstructed from the payment row, and a phantom one
cannot be reconstructed from anything.

**Accept the window and reconcile.** Defensible, and it is what phase 8's
reconciliation does *anyway* as a backstop. Rejected as the primary mechanism
because it makes the normal path lossy and the recovery path load-bearing, and a
recovery path that runs constantly is not a recovery path.

**Transactional outbox.** The event is a row in the same database, written in
the same transaction as the payment. A relay moves rows to Kafka afterwards.
Atomicity is the local transaction's, which already works.

## Decision

The outbox, with **two relay implementations kept selectable**: polling and
Debezium CDC. `EVENTS_PUBLISHER=direct` also remains runnable, so the broken
"before" can be re-measured rather than quoted.

CDC beats polling **5 ms against 260 ms p50** and is still not the default —
it needs a connector, a replication slot and an operator who understands both.

## Consequences

- The event is now **at-least-once, never exactly-once**. Every consumer needs
  idempotency, which is why `uq_entry_event_account` exists in the ledger.
- **Ordering is per partition**, so the partition key must be `paymentId` and
  cannot later become anything else without breaking per-payment ordering.
- The outbox table is on the hot path of every payment write. It grows, it
  needs sweeping, and its index is consulted on every insert.
- **The machinery introduced two worse bugs than the one it fixed**, and this is
  the consequence worth writing down. The relay's claim query took locks that
  blocked the payment path — `Lock wait timeout` on the payment's own commit —
  and Spring's `@Transactional` was silently inert on a self-invoked method, so
  the relay ran with no transaction at all and nothing said so. A mechanism that
  removes a rare failure by adding a permanent component is not obviously a
  trade in your favour, and it was not until both were found.
