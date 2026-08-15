# Chaos experiments

One page per experiment. The first is `00-baseline.md`, written in phase 2,
before any resilience component exists.

## Required structure

Every page carries the same five sections, and the order matters:

1. **Hypothesis** - written *before* the run. What is expected to break, in what
   order, and at roughly what numbers.
2. **Setup** - the exact chaos configuration, load profile and duration. Enough
   that the run can be reproduced.
3. **Graph** - the measurement.
4. **Actual result** - what happened, with numbers.
5. **What surprised you** - the gap between 1 and 4.

Section 5 is the point of the whole exercise. An experiment that confirms the
hypothesis exactly teaches nothing and usually means the hypothesis was written
after the run.

## Rules

- **One fault at a time.** Two active chaos sources means the cause of a failure
  cannot be attributed to either.
- **Hypothesis before experiment.** Writing the prediction down first is what
  makes the gap visible; it stops recall from quietly rewriting what was
  expected.
- **Chaos without concurrency is invisible.** A bulkhead only rejects when
  in-flight calls exceed its limit, and a breaker only trips on a *rate* of
  failures within a window. Faults must be applied under k6 load or they are
  merely faults, not chaos.

## Chaos layer map

| Layer | Tool | Injects |
|---|---|---|
| Downstream PSP | `mock-psp-simulator` | Business-level: latency, errors, hangs, duplicates |
| Network / connections | Toxiproxy | Latency, timeout, `reset_peer`, bandwidth |
| In-process beans | Spring Boot Chaos Monkey | Generic latency and exceptions on service/repo beans |
| Bespoke seams | `chaos-core` | Sleep-in-held-lock, per-consumer exceptions |
| Process / container | Pumba | `kill`, `pause`, SIGTERM |
| Concurrency | k6 | The load that makes any of the above chaotic rather than merely faulty |

Toxiproxy breaks the **link**, Pumba breaks the **process**, Chaos Monkey breaks
the **bean**. They are not interchangeable.

## Index

*(empty - phase 2 writes `00-baseline.md`, the "before" half of every graph that
follows)*
