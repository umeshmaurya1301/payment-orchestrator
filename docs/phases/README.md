# Phase implementation guides

One document per phase. Each carries the same sections:

| Section | Purpose |
|---|---|
| **Goal** | The one sentence that decides whether the phase is done |
| **Why here** | Why this phase sits at this point in the order, and what breaks if it moves |
| **Prerequisites** | What must be true before starting |
| **Implementation** | Numbered, concrete build steps |
| **Key decisions** | The choices worth defending, with the reasoning - ADR seeds |
| **Exit criteria** | Checkable, not vibes |
| **Traps** | Things that fail silently or cost a day |
| **Interview payload** | What this phase lets you claim, and the follow-up question to be ready for |

## Index

| Phase | Document | Est. | Status |
|---|---|---|---|
| 0 | [Foundations](00-foundations.md) | 1 wk | **done** |
| 1 | [Vertical slice](01-vertical-slice.md) | 2 wk | next |
| 2 | [Chaos harness and load](02-chaos-harness.md) | 1 wk | |
| 3 | [Resilience layer](03-resilience.md) | 3-4 wk | |
| 4 | [Observability](04-observability.md) | 2 wk | |
| 5 | [Health-driven routing](05-health-routing.md) | 2 wk | |
| 6 | [Async spine](06-async-spine.md) | 3 wk | |
| 7 | [Concurrency and idempotency](07-concurrency-idempotency.md) | 2-3 wk | |
| 8 | [Data layer depth](08-data-layer.md) | 2 wk | |
| 9 | [gRPC, security, data protection](09-grpc-security.md) | 3 wk | |
| 10 | [Packaging](10-packaging.md) | 1 wk | |

Total ≈21-23 weeks at 6-10 hrs/week.

## The rules that hold across every phase

1. **Never add a resilience component without first observing the failure it
   prevents.** Break it, measure it, then fix it. Every phase from 3 onward
   produces a before/after graph.
2. **One fault at a time.** Two active chaos sources means you cannot attribute
   what broke.
3. **Hypothesis before experiment.** Write down what you expect, then run it.
   The gap between prediction and reality is the learning, and it is the single
   most convincing thing to narrate in an interview.
4. **Everything reusable lands in `infra-core`.** The library and the platform
   are two entries where one depends on the other.
5. **Raw PAN never leaves the edge.** Tokenize on arrival. Every downstream
   service, log line, Kafka message and DB row sees `bin + token + last4`.
   Build the habit in phase 1, not as a cleanup pass - logging a card number
   once in dev is how the habit forms wrong.

## Sequencing

Phases are sequential. Sub-steps within a phase are not, except in phase 3,
where the whole point is that components are added and measured **one at a
time**.

Two phases are load-bearing in a way that is easy to underestimate:

- **Phase 2** is what turns this from "I used Resilience4j" into a chaos
  engineering story. It produces the baseline failure report that is the
  "before" half of every graph afterwards. Do not skip or shorten it.
- **Phase 10** is what makes the work legible to someone who has 20 minutes.
  Unpackaged work reads as unfinished work.
