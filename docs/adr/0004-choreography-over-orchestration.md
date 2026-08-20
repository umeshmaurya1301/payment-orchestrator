# ADR 0004 — Choreography over orchestration for the capture saga

**Status:** accepted (phase 6k) · **Evidence:** [experiment 16](../experiments/16-compensating-reversal.md)

## Context

A capture succeeds at a provider and the ledger cannot record it — after 5 s,
1 m and 10 m of retries. Both services did their jobs; what failed is the
agreement between them, and neither can see it. Something has to undo the
capture.

There was never a moment when one lock could have covered the provider, the
orchestrator's database and the ledger's database. That is what makes this a
saga rather than a transaction.

## Options considered

**Orchestration — a central saga coordinator.** One component owns the flow,
calls each step, and issues compensations. Its real advantage is that the state
of a saga is *in one place*: you can query "where is this saga" and get an
answer, which at fifteen steps is the difference between an operable system and
an archaeology exercise.

Rejected at this size. Three steps and two services do not need a coordinator,
and adding one means a new deployable that is on the critical path of every
payment, plus a second state machine that can disagree with the payment's own.

**Synchronous compensation — the ledger calls back into the orchestrator.**
Simplest to follow. Rejected because it makes the ledger depend on the
orchestrator being up at exactly the moment the system is already unwell, and
because it puts a provider call on a Kafka consumer thread with no retry ladder
behind it.

**Do nothing; let reconciliation catch it.** Phase 8 will find the disagreement
eventually. Rejected as the primary mechanism because "eventually" is a day, and
for a day the money is in the wrong place with nothing recorded about it.

**Choreography — the ledger publishes a compensation *request*; the orchestrator
decides.** No service commands another. The orchestrator is free to answer
`NOT_CAPTURED` and do nothing, and often does.

## Decision

Choreography, with the compensation as a **request** rather than a command, on
its own topic with blocking retry into a dead-letter queue.

## Consequences

- **The saga's state is distributed**, which is the cost. "Why was this payment
  reversed" is answered by reading two services' logs and a Kafka topic, not one
  table. At fifteen steps this would be the wrong choice.
- The compensation topic uses **blocking** retry while the ledger uses a
  non-blocking ladder, and that looks inconsistent until you notice the topic
  carries at most one record per incident — there is no line behind a stuck
  record to block, and ordering matters more because a compensation is a state
  transition.
- **The reversal cancels the authorization, not the capture**, because the
  capture legs are the ones that never posted. Getting this backwards would keep
  `SUM(amount_minor)` at exactly zero while leaving two accounts permanently
  wrong — the second time in two phases that the balanced-books invariant proved
  unable to see a real error.
- A `reversed_capture` tombstone is needed because the phase-6f **DLQ replay
  tool will happily push a compensated capture back through days later**, and
  every idempotency defence in the ledger says go ahead: that event genuinely
  never posted.
- One race is **not** closed: a replay already in flight when the reversal
  commits. It is named in the experiment rather than papered over.
