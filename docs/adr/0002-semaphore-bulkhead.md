# ADR 0002 — Semaphore bulkhead over a thread-pool bulkhead

**Status:** accepted (phase 3d) · **Evidence:** [experiment 04](../experiments/04-bulkhead.md)

## Context

One slow provider must not consume every resource the service has. The classic
answer is a bulkhead, and it comes in two shapes: a bounded semaphore around the
call, or a dedicated thread pool per provider.

Every service here runs on **virtual threads**, which changes the arithmetic that
made thread-pool bulkheads the default advice.

## Options considered

**Thread-pool bulkhead.** One pool per provider; a saturated provider exhausts
its own pool and nobody else's. This is Hystrix's model and it is what most
people mean by "bulkhead". Its real advantage is that it can **interrupt** a
call, because the call owns a thread the pool controls.

Rejected here on measurement: **3.7× worse tail latency** than the semaphore.
Under virtual threads the pool is pure overhead — a platform-thread pool in front
of work that would otherwise be a virtual thread reintroduces exactly the
handoff, queueing and context switching that virtual threads remove, and the
isolation it buys is isolation a semaphore also provides.

**No bulkhead; rely on the deadline budget.** The budget already bounds how long
any one call may take, so a slow provider cannot hold a thread forever. Rejected
because bounded duration is not bounded concurrency: 500 concurrent calls each
lasting the full budget still exhaust the connection pool, and the budget only
decides when they give up.

**Semaphore bulkhead.** A permit count per provider. Cheap, no extra threads, and
composes with the deadline.

## Decision

Semaphore, per provider, sized from the provider's contracted concurrency.

## Consequences

- **No interruption.** A semaphore cannot cancel an in-flight call; it can only
  refuse new ones. The deadline layer has to do the cancelling, which is why
  `DeadlineExecutor` exists and why it is more complicated than it looks.
- **The experiment did not justify this component**, which is the consequence
  worth being honest about. The bulkhead delivered **92% less load on a failing
  provider** and simultaneously took success against a merely *slow* provider
  from **100% to 6.8%** — it refuses calls that would have succeeded. And the
  edge still ran out of memory, so the bulkhead did not fix the thing it was
  reached for. Phase 3e's rate limiter is what fixed that.
- It is kept because 92% less load on a dead provider is worth having, and
  because the failure it causes (a fast rejection) is better than the one it
  prevents (a slow collapse). That is a judgement, not a measurement.
