# 17 — The graceful shutdown that could never have finished

**Phase 7i.** `tools/loadtest/graceful-drain.sh`

---

## Hypothesis

The exit criterion: *"Pumba SIGTERM mid-payment → in-flight requests complete or
land in `UNKNOWN`; nothing lost."*

Note that `UNKNOWN` is an **acceptable** outcome. The system is not required to
finish every payment across a restart — it is required to never be unable to say
what happened to one. A payment stranded in `AUTHORIZING` is the failure: the
money may or may not have moved, and nothing will ever resolve it. That is
precisely the state phase 3a built `UNKNOWN` to prevent.

Phase 7i found the bug before this drill existed, and it was not in any code. It
was three numbers that were each reasonable and had never been compared:

```
stop_grace_period  >  timeout-per-shutdown-phase  >=  DEADLINE_BUDGET_MS
     45s           >             35s              >=        30s
```

Six services declared `server.shutdown: graceful`. The compose file said nothing
about grace periods, so Docker used its default of **10 seconds** — and Spring
began a 35-second drain that was killed at 10. The prediction: with the chain
stated, nothing is stranded; with Docker's default, payments are.

## Setup

The two arms are **one flag apart**. `docker stop -t N` overrides
`stop_grace_period` for that one call, so the before and after differ by a single
number — no config edit, no rebuild, no restart of anything not being measured.

| Arm | | What it shows |
|---|---|---|
| **A** | `-t 45` | The configured grace period. Spring's 35s drain fits inside it |
| **B** | `-t 10` | Docker's default — what this project ran under until 7i |

A burst of payments is sent in the background and the signal lands **while they
are in flight**.

```
tools/loadtest/graceful-drain.sh
```

## Graph

```
                    grace   stop took   exit               final states        stranded
  ARM A  -t 45       45s        25s     143 (clean)        AUTHORIZED=76              0
  ARM B  -t 10       10s        11s     137 (SIGKILL)      AUTHORIZED=40
                                                           AUTHORIZING=36            36
                                        ^^^^^^^^^^^^
                                        the drain was cut off
```

Arm A's `stop took 25s` is the drain genuinely running — the provider is answering
in 18s and the server waits for those requests before exiting. Arm B is killed at
11s with 36 payments still in flight, and they never come back.

## Actual result

Both arms, one run, identical load:

```
   ok   45s grace > 35s drain >= 30s request budget

   ARM A - THE CONFIGURED GRACE PERIOD - docker stop -t 45
   --   stop took                                            25s
   --   container exit code                                  143 (clean)
   --   final states                                         AUTHORIZED=76
   ok   payments stranded in a non-terminal state            0

   ARM B - THE OLD TEN-SECOND CUTOFF - docker stop -t 10
   --   stop took                                            11s
   --   container exit code                                  137 (SIGKILL - drain was cut off)
   --   final states                                         AUTHORIZED=40 AUTHORIZING=36
   ok   stranded, as the pre-7i config must                  36
```

**36 payments stranded in `AUTHORIZING`** under the configuration this project
was running for six phases. Each one is a payment where the card may have been
charged and no part of the system will ever find out — not `FAILED`, not
`UNKNOWN`, just stuck. `UNKNOWN` at least has a poller coming for it in phase 8;
`AUTHORIZING` has nothing.

The exit codes are the mechanism, visible from outside the container: **143** is
a clean SIGTERM exit, which is what a completed drain looks like. **137** is
SIGKILL — Docker ran out of patience while Spring was still draining.

## What surprised me

**The drill passed before it measured anything, and the pass was worthless.** The
first run reported this:

```
   ARM B   exit code    137 (SIGKILL - drain was cut off)
   ok      payments stranded in a non-terminal state    0
```

Both statements are true and together they are damning. The mechanism fired —
Docker really did kill a draining server — and **nothing was lost, because
nothing was in flight**. Against a provider answering in ~200ms the whole burst
finishes inside ten seconds, so at the moment the axe fell there was no cargo.

An arm whose job is to reproduce harm and which cannot fail is the same problem
as an alert that never fires: experiment 07 found three of four alerts unable to
fire, and this is that finding wearing different clothes. The fix is to slow the
providers to 18 s — comfortably inside the 30 s deadline budget, so these are
legitimate in-flight requests rather than timeouts — which guarantees requests
are still running when the grace period expires.

**So the two arms now assert opposite things.** Arm A must strand nothing: the
fix works. Arm B must strand **something**: the thing being fixed was real. A
drill demanding zero from both reports PASS on precisely the run where it proved
nothing, which is how the first version behaved.

**The preflight was checking a label Docker Compose does not emit.** Before any
of the above, the drill refused to run at all:

```
   stop_grace_period    UNSET - Docker default is 10s
   XX  stop_grace_period is unset ... This is the phase-7i bug.
```

The container's actual `.Config.StopTimeout` was **45**. The check read
`.Config.Labels["com.docker.compose.stop_grace_period"]`, which this version of
Compose does not set, so it came back empty on a correctly configured stack. A
guard written to catch a missing grace period reported it missing **every time** —
a false negative in the one direction that matters, because a real regression
would have hidden behind a failure everybody had learned to expect and skip past.

**The bug was in the gap between two correct files.** Worth restating because it
is the reason this phase exists: `server.shutdown: graceful` in six services was
right, and a compose file that did not mention `stop_grace_period` was
unremarkable. Neither is expressed in terms of the other, no tool compares them,
and the result was six services that claimed a graceful shutdown and could never
have completed one. The preflight now asserts the *relationship* rather than the
values, which is the only form of the check that survives somebody raising the
deadline budget.

## Standing questions

- **The drill kills one service.** `payment-orchestrator` is where a stranded
  payment is visible, so it is the right one to start with — but `payments-edge`
  holds the idempotency records and `psp-connector` holds the vault connection,
  and neither has been SIGTERM'd mid-flight.
- **Nothing tests SIGKILL with no SIGTERM at all.** A `docker kill`, an OOM, or a
  node disappearing gives no drain at any grace period. The correct answer there
  is the same as arm B's — stranded payments — and the real fix is phase 8's
  reconciliation rather than a longer timeout.
- **36 stranded payments are still in the database.** The drill measures and does
  not clean up, deliberately: they are exactly the input phase 8's poller needs,
  and manufacturing them by hand would be a worse test than inheriting real ones.
