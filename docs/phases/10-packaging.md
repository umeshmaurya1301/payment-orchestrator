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

#### 10b: the audit, and what it found

Seventeen pages exist. Checked mechanically for the five sections, then read
where the check disagreed with the prose:

| Pages | State |
|---|---|
| 00–03, 09–15 | All five sections present |
| **04–08** | **No "what surprised me" section at all** — five pages, all from phases 3d–4f |
| 16 | Hypothesis and assertions only, correctly — it has not been run |

The material is plainly there in every one of the five: page 04 is titled around
*"the first component the experiment did not justify"*, 05 has a section called
*"where I got the design wrong"*, 07 opens on three alerts that could not fire.
Those are surprises. They are simply not collected under the heading the phase
asks for, so the criterion is **not** ticked.

**I have deliberately not written those five sections.** They are first-person
accounts of runs performed in phases 3d–4f, and composing "what surprised me"
for somebody else's experiment is the exact failure the trap list describes as
*"unrecoverable a month later"* — the recoverable-looking version is worse than
the gap, because it reads as genuine. The gap is recorded here instead.

#### 10b, as built

- **The data-flow diagram** and audit-scope boundary are in the README, with the
  three enforcement mechanisms named separately: a database grant, a method
  signature, and the log allowlist.
- **How erasure propagates** is new — it did not exist before 9b/9c — and it
  states the part that is easy to get wrong: deleting the mapping row is *not*
  erasure, because a restore brings it back. The key is what is destroyed, and
  the test that proves it restores a backup.
- **Resume bullets** are in [`docs/resume-bullets.md`](../resume-bullets.md),
  split into *measured* and *built but not yet measured*, with the bulkhead
  explicitly excluded because the experiment did not justify it.

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

#### 10a, as built

All eight are written, in [`docs/adr/`](../adr/). Each carries context, the
options genuinely considered, the decision, and consequences — with every number
traceable to a page in `docs/experiments/`.

**Two of them conclude against the thing that was built**, which is what the
trap list is asking for when it says an ADR whose every option loses to the
built one reads as rationalisation:

- [0002](../adr/0002-semaphore-bulkhead.md) records a bulkhead **the experiment
  did not justify** — 92% less load on a dead provider, and success against a
  merely *slow* one falling from 100% to 6.8%, with the edge still running out
  of memory. It is kept on a judgement, and the ADR says it is a judgement.
- [0001](../adr/0001-outbox-over-2pc.md) records machinery that **introduced two
  worse bugs than the one it fixed** before it worked: a relay whose claim query
  blocked the payment path, and a `@Transactional` that was silently inert on a
  self-invoked method.

[0006](../adr/0006-retry-budget.md) states its cost as a number rather than a
preference — **67% success instead of 94%**, deliberately — and
[0007](../adr/0007-tokenization-boundary.md) claims **three components in PCI
scope, not one**, which is the claim the phase's own trap list warns against
shrinking.

The index also carries the four-question PCI table, and is explicit that the
fourth question — *who read what* — is **not answered yet**.

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

- [x] README opens with the architecture diagram and the traffic-shift graph —
      the graph has been there since phase 5; the diagram was added in 9d, as a
      mermaid flowchart rather than an image so it survives a diff and cannot
      drift from the compose file unnoticed. It marks the two boundaries that
      are the actual design: the **dashed edges are the only paths a card number
      travels** (three places, two databases with disjoint credentials), and the
      **double line is the atomicity boundary** where the state change and the
      owed event are one transaction
- [~] Every experiment has a page with all five sections, including "what
      surprised you". Audited in 10b: **five pages (04–08) have no such
      section**, though each contains the material. Not written on the
      author's behalf — see above
- [x] 6-8 ADRs, each with a genuinely considered rejected option — 10a.
      Eight, in [`docs/adr/`](../adr/), two of which conclude against the
      component they document
- [x] PII/PCI section with a data-flow diagram — 10b. In the README, with the
      audit-scope boundary, the three enforcement mechanisms, and how erasure
      propagates through copies that can never be rewritten
- [x] 90-second demo rehearsed end to end from a cold start —
      [`tools/demo/90-second-demo.sh`](../../tools/demo/90-second-demo.sh).
      Runs against a freshly-started stack (nothing pre-warmed with traffic
      before the clock starts) and verifies every claim it makes rather than
      narrating them - two consecutive rehearsals both PASS, at **62s and
      57s**, comfortably under budget:

      | step | measured |
      |---|---|
      | load flowing before judging health | 6-46 attempts in an 8s window |
      | traffic off psp-a after the fault | **2s and 6s** across the two runs |
      | end-user error rate during the fault | **1%** both times - flat, not a spike |
      | trace spans the payment path | **4** services found (edge, orchestrator, connector, *and* the simulated provider - one hop further than asked, because `mock-psp-simulator` carries the same instrumentation) |

      **SigNoz was not actually wired up when this started**, despite
      `docker/signoz/payorch-obs.override.yml` and `tools/obs/signoz.sh`
      existing and being fully documented from phase 4 - the stack had never
      been *started* in this environment. Traces were being generated and
      exported to nothing, silently: `ObservabilityDefaults` points a service
      with no collector at `http://localhost:4318` precisely so a developer
      without SigNoz sees no errors, which means a developer *with* SigNoz not
      yet started also sees no errors, and the distinction between those two
      cases is not visible from a service's own logs. Only running the actual
      demo step - open a trace - would have caught it.

      **The demo script itself failed its first rehearsal, in a way worth
      recording.** `soak.js`'s `setup()` resets the simulator's fault state via
      a `SIMULATOR` URL defaulted to `localhost:8085` - correct on a host
      machine, and inside the k6 container that address is itself, not
      `mock-psp-simulator`. The whole k6 run aborted in `setup()` before a
      single payment was sent, and every check downstream still had a plausible
      surface reading: "traffic off psp-a within 20s" reported **2 seconds**,
      because an empty window divides to a 0% share and looks exactly like an
      instant, perfect shift. The fix was not the missing env var alone - it
      was requiring a minimum sample size before trusting any share computed
      from it, the same trap `rest-vs-grpc.sh` and `mtls-demo.sh` hit earlier
      this session in different shapes: a script that can pass while measuring
      nothing is worse than one that fails
- [x] Resume bullets, each traceable to a measured number —
      [`docs/resume-bullets.md`](../resume-bullets.md). Twelve measured, four
      built-but-unmeasured in a separate table, and one component deliberately
      left out because its experiment did not justify it

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
