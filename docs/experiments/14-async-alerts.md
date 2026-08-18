# 14 — Alerting on lag and dead letters

**Phase 6i.** `tools/obs/async-alert-drill.sh`

---

## Hypothesis

Phase 6's last exit criterion:

> Alerts on consumer lag and DLQ depth fire during the chaos run

Two rules, then. The prediction, written before anything was built:

1. The metrics already exist. Phase 6f added counters, phase 6e added the ledger,
   `/actuator/dlq` already reports `pending` separately from `records`. This is a
   half-day of writing two SigNoz rules.
2. **DLQ *depth* is the wrong number.** Phase 6f already recorded why: a Kafka
   topic is a log, replaying moves an offset rather than deleting anything, so
   record count rises forever. A rule on it fires during the first incident and
   never stops. `pending` is the number that returns to zero.
3. A lag threshold will fire when the ledger falls behind.

Prediction 1 was wrong, and prediction 3 is wrong in the way that matters.

## Setup

Three arms, because a lag alert has two failure modes and they are not the same
incident.

| Arm | Fault | What it tests |
|---|---|---|
| 1 | Consumer paused 3 s per record, 250 payments | the threshold path |
| 2 | Consumer **frozen** with SIGSTOP, 40 payments | the *absence* path |
| 3 | 5 records produced straight onto the DLQ topic | `pending`, and its return to zero |

Arm 3 does not drive the retry ladder. `tools/loadtest/retry-dlq.sh` already
proves a failing message walks 5 s, 1 m and 10 m into the DLQ, and it takes
twelve minutes to do it. This drill measures the **watcher**, and a twelve-minute
setup would only make the alerting pipeline's own latency harder to see.

```
tools/obs/signoz.sh apply          # push rules 05 and 06
tools/obs/async-alert-drill.sh     # ~45 minutes
```

## Graph

State of each rule against the metric it is watching, sampled every 30 s.

```
ARM 1 - SLOW CONSUMER                      ARM 2 - WEDGED CONSUMER
  t     rule        payorch_consumer_lag     t     rule        lag series
  ----  ----------  --------------------     ----  ----------  ----------
    0s  inactive    220                        0s  inactive    0
   30s  inactive    190                       60s  inactive    0
   60s  inactive    160                      120s  inactive    0
   90s  inactive    130                      240s  inactive    0
  120s  inactive    100                      360s  inactive    0
  150s  inactive     70                      390s  FIRING      0   <- on silence
  180s  FIRING       40   <- below 50!
        ...disarm...                               ...unpause...
   30s  firing        0                       30s  firing       0
  240s  inactive      0                      210s  inactive     0

ARM 3 - DEAD LETTERS
  t     rule        payorch_dlq_pending
  ----  ----------  -------------------
    0s  inactive    5
  180s  FIRING      5
        ...triage: advance the replay group...
    0s  firing      0
  510s  inactive    0
```

## Actual result

### A. There was no lag metric at all

The first thing the drill needed was the thing that did not exist.
`/actuator/prometheus` on `ledger-notifier` listed **80 metric families and not
one of them was lag**:

```
$ curl -s localhost:8084/actuator/prometheus | grep -c '^# TYPE'
80
$ curl -s localhost:8084/actuator/prometheus | grep -c 'kafka_consumer'
0
```

Boot attaches a `MicrometerConsumerListener` to the `ConsumerFactory` it
autoconfigures. This service builds its own — for the deserializers, the
`earliest` reset, the disabled auto-commit and the `allow.auto.create.topics`
setting phase 6f added — and hand-building it silently opts out of every
`kafka.consumer.*` meter. The exit criterion asked for an alert on consumer lag,
and for six units there had been nothing to alert on.

One line fixes it, and produced 60 series. It is also **not sufficient**, which
is the next finding.

### B. The client-side metric disappears exactly when it matters

`records-lag-max` is computed *by the consumer, from fetches the consumer made*.
Arm 2 freezes the consumer with SIGSTOP and publishes 40 payments into a topic
nobody is reading:

```
   before: client-side lag series  60
           payorch_consumer_lag    0
   ledger-notifier PAUSED (SIGSTOP - frozen, not shut down)
   after:  client-side lag series  0    <- the metric is gone
           payorch_consumer_lag    ''   <- so is this one
```

Sixty series to zero. A rule saying `records-lag-max > 50` cannot fire, because
there is no series for the condition to be true of — and this is the **worse**
incident, the one where the backlog grows without bound. It is phase 4e's finding
in a new costume: an alert that looks correct in the UI and is unfireable for the
case it exists to catch.

`payorch.consumer.lag` — end offset minus committed offset, read from the broker
with an `AdminClient` — is true whether or not the consumer is alive. And it
vanished too, because it is published *by the service that died*. A lag monitor
that shares a fate with the thing it monitors is half a monitor.

So the rule pairs its threshold with `alertOnAbsent: true, absentFor: 3`, and
**silence is the alert**. Arm 2 is the only thing that proves that path works:

```
   [ 390s] firing         0
   ok   consumer-lag fired on NO DATA                  firing
```

**6.5 minutes** from freeze to page. For a ledger that is arguably too slow, and
it is a standing question rather than a settled number.

### C. The alert fires about the past

Arm 1 is the well-behaved case, and it produced the most instructive line in the
run:

```
   [ 150s] inactive       70
   [ 180s] firing         40      <- the live lag is BELOW the threshold of 50
```

The rule fired when the condition was no longer true. It was judging a window
that ended two minutes earlier, where lag was 160. `evalWindow` is 2 m and SigNoz
adds an undocumented `eval_delay` of 2 m on top — a fact `tools/obs/alert-drill.sh`
had to discover the hard way in phase 4e, and which this run confirms from the
other direction:

| | |
|---|---|
| lag reached zero after the disarm | t + 30 s |
| `consumer-lag` went inactive | t + 240 s |
| difference | **210 s ≈ evalWindow + eval_delay** |

Nobody reading a dashboard would guess that the number on screen and the number
the alert is judging are four minutes apart.

### D. `pending`, and what "at least once over 5 minutes" costs

Arm 3 fires at t+180 s on five records and resolves at **t+510 s** — more than
twice arm 1's recovery. The two rules are built differently on purpose and the
difference is visible in exactly this number:

| | `consumer-lag` | `dlq-pending` |
|---|---|---|
| window | 2 m | 5 m |
| match | all the time | at least once |
| resolves after | 210 s | 510 s |

"At least once" means the whole window has to scroll past the last non-zero
sample before the rule can go quiet, so a 5-minute window costs 5 minutes of
recovery plus the delay. That is the right trade for a dead letter — a spike that
drains is *not* healthy here; a message that reaches the DLQ has already
exhausted 5 s, 1 m and 10 m of retries and is not going to fix itself — and the
wrong trade for lag, where a spike that drains is a consumer doing its job.

The resolution path also had to be a **triage** rather than a replay. The five
records are synthetic; replaying them would push nonsense onto `payment.events`
and into the ladder. Advancing the `ledger-dlq-replay` group past them is what an
operator does when they have read a dead letter and decided it is not replayable,
and it produces the same state transition the endpoint does.

### E. What the channel actually received

```
     firing         consumer-lag
     resolved       consumer-lag
     firing         dlq-pending
```

Three of four, and the fourth is a timing artefact rather than a fault: the
`dlq-pending` resolved notification is dispatched after the state flips, and the
drill prints its summary in the same 30-second poll that saw the flip. Worth
keeping the check anyway — a rule that evaluates correctly and notifies nobody is
still a failure, and it is the half that is easy to forget.

## What surprised me

**The drill's first run was compromised by its own subject.** Preflight printed
`rule consumer-lag  firing` — correctly, on `alertOnAbsent`, because the metric
had been deployed four minutes earlier and everything before that was no-data.
Arm 1 would then have "passed" the instant it looked. That is the same family as
experiment 07's false passes and phase 5c's three green failover drills: a drill
that does not establish its own baseline is measuring the state it inherited.
The fix is a preflight that waits for both rules to go quiet and refuses to run
if they will not.

**`docker stop` did not hold, and I never found out why.** Arm 2 originally
stopped the container. It was running again **39 seconds later, with
`RestartCount=0`** — so something started it explicitly rather than a restart
policy reviving it; `docker events` showed no `die`/`start` pair for the window.
The cause is unresolved. What it did to the drill is the point: the consumer came
back inside the absence window, the series returned, and the arm would have
reported "the alert did not fire on no data" about an alert that was never shown
any no-data. Switching to SIGSTOP removed the question, and is the better fault
regardless — a stopped consumer leaves its group cleanly and the coordinator
knows; a frozen one holds its partition assignment until `session.timeout.ms`,
which is what a GC pause or a descheduled pod actually looks like.

**A sixth thing was doing nothing.** `/actuator/ledger` has been listed in
`management.endpoints.web.exposure.include` since phase 0 and has always returned
404, because no bean ever contributed that endpoint id. Harmless on its own, and
worth closing because "the config mentions it" is the exact evidence that stopped
anyone checking `/actuator/prometheus` on this service for four phases. It now
serves the consumer counters and the backlog snapshot.

**Writing the rule was the easy half; deciding what a backlog IS took the time.**
Three numbers were candidates for "DLQ depth" — records, pending, and the
ledger's own dead-lettered counter — and they answer different questions. Two of
them can never return to zero. The rule that ships thresholds the one that can,
and publishes the other as context, because 12 pending out of 12 records is a new
incident and 12 out of 4,000 is a Tuesday.

## Standing questions

- **6.5 minutes to page on a dead consumer.** `absentFor: 3` plus `eval_delay`
  plus evaluation frequency. Halving `absentFor` halves the detection time and
  risks flapping on a rolling restart, which the graceful-shutdown work in
  phase 7 will make measurable rather than a guess.
- **The lag exporter should not live in the consumer.** It does, and arm 2 is the
  demonstration of why that is a compromise: the gauge died with the service and
  only `alertOnAbsent` covered the gap. Kafka Exporter or Burrow is the honest
  fix, and it is infrastructure this project has otherwise avoided.
- **`payorch.webhook.refused` has no rule.** Phase 6h left it as a counter. It
  climbing means merchants are not being told about payments that happened, and
  nothing currently watches it.
- **Lag is measured for the main topic only.** The three retry tiers and the DLQ
  have their own consumer groups and their own ways of falling behind, and a
  backlog on `payment.events.retry-600000` is invisible to every rule here.
