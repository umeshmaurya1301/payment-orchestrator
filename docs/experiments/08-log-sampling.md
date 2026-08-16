# 08 — Log sampling (phase 4f)

The last item in phase 4, and the one the phase plan explicitly said to do
**last**: *"turn sampling on after correlation works, or you will debug the
pipeline with 1% of the evidence."* Correlation landed in 4d, so this was
unblocked rather than merely next.

> **Headline.** Keeping every log line costs **4.00 lines and 2,514 bytes per
> payment** — 75 MB/minute at the 500 rps where phase 2 found this system's
> ceiling, to record that several million things went exactly as expected.
> Trace-based sampling at 1% removes **97.6% of lines and 97.7% of bytes**, and
> under a 50% error injection it kept **2,325 of 2,315 failed payments' log
> lines — 100%** — while INFO fell to 0.8%.
>
> The finding is not the ratio. It is that sampling **silently defeats the
> PAN-leak build test**, which scans container output line by line and would
> have inspected 1% of it while still reporting green. Two controls from the
> same phase, one quietly disabling the other. Sampling is therefore off by
> default and the scanner now refuses to run against sampled logs.

---

## Hypothesis

Written before the runs.

> Every service already emits structured JSON with `traceId` in MDC, so the
> mechanism should be a filter and a hash, and the interesting part should be
> choosing what "an error" means when the decision has to be made on the first
> line rather than the last.
>
> I expect the cost of logging everything to be large but not alarming — the
> lines are ~600 bytes and there are a handful per payment — and I expect the
> reduction to land close to the configured rate.
>
> **Least confident about:** whether "100% of errors" is achievable head-based
> at all. A request is not known to have failed until it has failed, and by then
> its earlier lines are already gone.

---

## A. What logging everything costs

Measured with no chaos, `ramp.js` at 100 rps, counting `docker logs` bytes
before and after against payments actually completed:

| service | lines/payment | bytes/payment |
|---|---|---|
| `payments-edge` | 1.00 | 672.9 |
| `payment-orchestrator` | 2.00 | 1,220.7 |
| `psp-connector` | 1.00 | 620.9 |
| **total** | **4.00** | **2,514.6** |

Exactly 4.00 — one line at the edge, two at the orchestrator (`payment
initiated`, `authorization completed`), one at the connector. The tidiness is
worth noting: this is a system whose logging is already disciplined, with no
per-loop chatter to trim. There is nothing to delete, only something to sample.

Extrapolated to the 500 rps at which phase 2 measured this system falling over:
**75.4 MB/minute**, about 108 GB/day, of which essentially all is successful
payments.

---

## B. The design: hash the trace id, not the line

The decision is `hash(traceId) < rate`, taken independently in every service.

That single choice is the whole design, and the alternative is worse in a way
that is easy to miss. Sampling **per line** at 1% yields 1% of the lines of 100%
of the traces: every trace present, none readable, and an artefact that looks
like evidence. Sampling **per trace** yields 1% of traces, complete — all four
lines, across all four services — because four separate JVMs hashing the same
trace id reach the same verdict with no coordination, no shared state and no
extra field on the wire.

It also makes the verdict stable: the same trace id always gets the same answer,
so a trace that was kept stays kept on re-read.

Implemented as a Logback **`TurboFilter`**, not an appender filter. An
appender-level filter runs after the `LoggingEvent` is constructed — message
formatted, arguments boxed, MDC copied — so it saves the I/O and none of the
work. A `TurboFilter` runs before the event exists, so a dropped line costs a map
lookup and an integer compare. Sampling that still allocated per request would be
spending the budget it exists to save.

Two things are never sampled:

- **WARN and above**, checked before the sampler is consulted at all. An error
  rate is not something to estimate from 1% of the evidence.
- **Lines with no trace id** — startup, shutdown, scheduled work. This project
  logs its effective configuration at startup, and every experiment writeup
  quotes those lines to prove which build produced its numbers. They are also
  negligible: dozens of lines against millions.

### What it deliberately does not do

It is **head-based**. A request that turns out slow or failed has already lost
its earlier INFO lines; only its WARN/ERROR lines survive, via the rule above.
Keeping the full history of every failed request means buffering every trace
until it completes and flushing on outcome — tail-based sampling, which wants
unbounded memory in precisely the incident where memory is already the problem.
Phase 2's baseline experiment ended in an `OutOfMemoryError`; adding a
per-trace buffer to that system is not a trade worth making here. The phase plan
calls it "the harder version" and it is deferred knowingly.

---

## C. What it actually removed

Same load, same profile, sampling at 1%:

| | lines/payment | bytes/payment | at 500 rps |
|---|---|---|---|
| every line kept | 4.00 | 2,514.6 | 75.4 MB/min |
| sampled at 1% | 0.097 | 58.4 | **1.8 MB/min** |
| | **−97.6%** | **−97.7%** | |

The per-service split is where it gets interesting:

```
payments-edge          0.009 lines/payment
psp-connector          0.009 lines/payment
payment-orchestrator   0.078 lines/payment     <- 8x the others
```

The orchestrator is not leaking. Breaking its surviving lines down by level:

```
WARN  authorization refused: provider circuit open    225
INFO  payment initiated                                36
INFO  authorization completed                          35
```

71 INFO lines out of 7,596 emitted — **0.93%**, landing on the configured rate.
The other 225 are WARN, kept in full because they are supposed to be. The
"anomaly" is the requirement working.

### 100% of errors, tested rather than asserted

The decisive run: 50% error injection, sampling still at 1%.

```
payments AUTHORIZED :    88
payments FAILED     : 2,315

log lines by level  : WARN 2,325   INFO 20

INFO kept       :    20 of ~2,491 emitted   =  0.8%   (target ~1%)
WARN/ERROR kept : 2,325 against 2,315 failures = 100%  (target 100%)
```

Every failed payment produced a log line. The 10 extra WARN lines are breaker
state transitions, which are also not sampled. Meanwhile INFO — the successful
noise — fell to 0.8%.

This is the property that matters and it is the one that is easy to get wrong,
because at a 50% error rate a naive per-line sampler would have discarded
roughly 1,150 failures and left an error count that looked like 1% of reality.

---

## D. The finding: two controls, one disabling the other

Phase 4's other deliverable is the **PAN-leak build test**, which runs the k6
suite against a live stack, captures every container's output and fails the build
if a Luhn-valid card number appears. It reads:

```bash
docker compose logs --no-color --no-log-prefix --tail=all
```

At 1% sampling that command returns 1% of the lines. A leaked PAN would then pass
the build with **99% probability** — silently, and differently on every run,
because which lines survive depends on which trace ids the load generator
happened to produce.

Neither control is wrong. Together they are, and nothing in either one says so.
The failure is not even detectable from the test's own output: it reports a clean
scan, having genuinely found nothing in everything it was given.

Three things follow, and all three are now true:

1. **Sampling is off by default.** `payorch.logging.sampling.success-rate`
   defaults to `1.0` — keep everything. Turning it on is an explicit override
   (`docker/log-sampling.override.yml`), taken by an environment whose volume
   justifies it.
2. **Every service asserts which way the switch is set**, at startup, unsampled.
   Enabled logs at WARN — deliberately louder than its neighbours — and says in
   the message that the PAN test must not be trusted against sampled logs.
3. **The scanner refuses to run against sampled logs.** It greps the captured
   output for those assertions and exits non-zero if it finds sampling enabled,
   *or if it finds no assertion at all* — because an image predating this change
   would also be silent, and that is exactly the case where a stale build could
   be sampling without saying so.

Point 3 is the same rule as the scanner's existing self-test, applied to a
different question. There it was *can this thing go red at all*; here it is *did
this thing see everything*. A control that cannot be shown to be working is not a
control, and "it found nothing" is not the same claim as "there is nothing".

---

## E. A bug the tests caught

The sampler's rule is "keep anything you cannot classify" — a null, short or
non-hex trace id is kept rather than dropped, because a malformed trace id means
something upstream is broken and that is not the moment to start discarding
evidence.

The first implementation checked the rate before the trace id, on the reasoning
that a rate comparison is cheaper than a string check:

```java
if (successRate >= 1.0) return true;
if (successRate <= 0.0) return false;   // <- returns before the guard below
if (traceId == null || traceId.length() < 13) return true;
```

At rate `0.0` a null trace id was dropped, while at every other rate it was kept.
The rule held at 99 rates out of 100. A test asserting the invariant at rate 0.0
found it immediately; nothing in normal operation would have, because rate 0.0 is
not a setting anybody uses — which is precisely why it was the one left broken.

---

## Standing questions

- **Tail-based sampling** would keep the full history of failed requests rather
  than only their error lines. It needs bounded per-trace buffering and a flush
  on outcome, and the bound is the hard part — the incident where you most want
  those lines is the incident where memory is scarcest.
- **Slow requests are not sampled in specially.** The phase plan asks for "100%
  of errors *and slow requests*"; a slow request that succeeds is currently
  sampled like any other success. Its latency is on the span and in the metric
  histogram, so it is not invisible — but its log lines are 1%-likely. Doing this
  properly is the same buffering problem as above.
- Nothing here samples **traces**, only logs. Tracing is still at 100%
  (`ObservabilityDefaults`), which is affordable at these volumes and will not be
  at phase 6's.
