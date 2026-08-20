# ADR 0003 — UUIDv7 stored as `BINARY(16)` for clustered primary keys

**Status:** accepted (phase 1) · **Evidence:** phase 8's index work

## Context

Every table needs a primary key. InnoDB clusters the table on it, and **every
secondary index carries a copy of it**, so the choice affects the size and
fan-out of every index in the schema — not just the one on the key.

The identifier is also needed *before* the insert: it goes into log lines, into
the attempt row, and into the response.

## Options considered

**Auto-increment `BIGINT`.** Smallest, fastest, perfect insert locality.
Rejected because the value does not exist until the insert returns, so it cannot
be logged or referenced beforehand; because it leaks volume (customer #4,182
knows roughly how many customers there are); and because it makes merging data
across environments or shards a renumbering exercise.

**UUIDv4 as `CHAR(36)`.** The default in most projects and the worst of the
options. 36 bytes in every secondary index instead of 16, stored as text so
comparisons are collation-dependent, and **random**, so every insert lands on a
different page — constant page splits and a clustered index that never stays
warm.

**UUIDv4 as `BINARY(16)`.** Fixes the size. Does not fix the randomness.

**UUIDv7 as `BINARY(16)`.** Time-ordered in its high bits, so inserts land
adjacently and the clustered index behaves roughly like an auto-increment one,
at 16 bytes, generated client-side before the insert.

## Decision

UUIDv7 as `BINARY(16)`, generated in the application.

## Consequences

- Every id is **16 bytes in every index**, which phase 8's index work depends on
  more than it looks: `(state, next_poll_at)` carries a `payment` id per entry.
- Ids **leak creation time**, and that is a real cost. It is acceptable for a
  payment id, which is already known to both parties at the moment it is
  created.
- **One table deliberately breaks this rule**, and the exception is the
  interesting part: `token_vault.token` is 22 characters of `SecureRandom`, not
  a UUIDv7. Time ordering would make neighbouring tokens guessable from one
  observed value, and on a table this small and this sensitive
  **unpredictability is worth more than insert locality**. A rule that has no
  exception has not been thought about.
- Values are opaque in a SQL console — `SELECT * FROM payment` shows binary
  gibberish, and every ad-hoc query needs `HEX()` or `UNHEX()`. This annoys
  somebody roughly once a week.
