# Phase 10 — Package it for interviews

| | |
|---|---|
| **Estimate** | ~1 week |
| **Depends on** | everything |
| **Delivers** | a README, ADRs, experiment writeups and a 90-second live demo |

## Goal

Make 21 weeks of work legible to someone who has 20 minutes.

## Why here

**Don't skip this either.** Unpackaged work reads as unfinished work. A reviewer
gives a personal project a few minutes; if the first screen does not show
something surprising, the rest is never read.

The single highest-leverage artefact is phase 5's traffic-shift graph, and it
belongs above the fold.

## Implementation

### 1. `README.md`

Architecture diagram and the **phase-5 traffic-shift graph up top**. Not the
module list, not the tech stack — the graph showing a provider degrading and
traffic draining away without an error spike.

Then: what it is, how to run it, and the phase index.

### 2. `docs/experiments/`

One page per chaos experiment. Each carries the five sections
`docs/experiments/README.md` already mandates:

1. Hypothesis (written before the run)
2. Setup
3. Graph
4. Actual result
5. **What surprised you**

Section 5 is the one that matters. An experiment that confirms its hypothesis
exactly teaches nothing and usually means the hypothesis was written afterwards.

Expected inventory: `00-baseline` (phase 2), six from phase 3, the traffic shift
(phase 5), broker kill and DLQ replay (phase 6), pool starvation, deadlock and
the virtual-thread benchmark (phase 7), the index table (phase 8), REST vs gRPC
(phase 9).

### 3. Six to eight ADRs

For the decisions you would be asked to defend. Each: context, options
considered, decision, consequences — including the ones you dislike.

| # | ADR |
|---|---|
| 1 | Transactional outbox over 2PC |
| 2 | Semaphore vs thread-pool bulkhead under virtual threads |
| 3 | UUIDv7 as `BINARY(16)` clustered primary key |
| 4 | Choreography over orchestration for the capture saga |
| 5 | MySQL/Mongo split — balances vs immutable journal |
| 6 | Retry budget over naive retry |
| 7 | Tokenization boundary and PCI scope |
| 8 | `infra-core` as an included build |

An ADR with no rejected option is a description, not a decision record.

### 4. The PII and PCI scope section

In the README, with a data-flow diagram showing precisely:

- where raw PAN exists and where it does not
- which components are in audit scope
- how erasure propagates

Given your card-issuance background this is a section you can write with real
authority, and almost no personal project has one.

### 5. A 90-second demo script

Runnable live, in order:

1. Start load (`ramp.js`)
2. Show the SigNoz dashboard — three providers, healthy
3. Degrade PSP-A via the simulator chaos endpoint
4. Watch traffic shift to B and C, error rate flat
5. Show the trace spanning edge → orchestrator → connector, logs correlated

Rehearse it. Ninety seconds means ninety seconds, and something will not start
the first time.

### 6. Resume bullets from measured numbers

Written from the experiment writeups, not from adjectives.

Not *"built a resilient payment platform"* but *"cut P99 from X to Y under a 40%
downstream error rate by adding a per-operation circuit breaker whose state
drives routing weight; measured with k6 at 500 rps."*

Every number should be traceable to a page in `docs/experiments/`.

## Exit criteria

- [ ] README opens with the architecture diagram and the traffic-shift graph
- [ ] Every experiment has a page with all five sections, including "what surprised you"
- [ ] 6-8 ADRs, each with a genuinely considered rejected option
- [ ] PII/PCI section with a data-flow diagram
- [ ] 90-second demo rehearsed end to end from a cold start
- [ ] Resume bullets, each traceable to a measured number

## Traps

**Writing the experiment pages at the end.** They must be written *during* each
phase — the hypothesis has to predate the run, and "what surprised you" is
unrecoverable a month later.

**A README that leads with the tech stack.** Nobody is impressed by a list of
libraries. Lead with the graph.

**ADRs written to justify what you did.** If every ADR concludes that the first
option was right, they read as rationalisation.

**A demo that needs a warm JVM and a lucky start.** Rehearse cold. Have a
recording as backup.

**Overclaiming scope.** "Raw PAN exists in one component" is wrong and an
interviewer will find the connector. Three components with one audited reversal
path is both true and impressive.

## Interview payload

This phase *is* the interview payload. The rest of the project generated
evidence; this phase makes it findable in the order a reviewer will want it:

1. The graph (why should I keep reading)
2. The experiments (this person measures things)
3. The ADRs (this person makes decisions and knows their cost)
4. The PCI section (this person has worked with regulated data)
