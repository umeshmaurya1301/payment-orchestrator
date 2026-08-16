# 06 — Dynamic config, and three providers (phase 3f)

The last sub-step, and the one 3d and 3e both ended by asking for. Everything
from 3a to 3e was in place and unchanged; the only new thing is where the
numbers come from.

> **Headline.** A bulkhead limit changed with one `UPDATE`, mid-run, took effect
> in under one poll interval and moved provider throughput from **8.0/s to
> 51.4/s** — no restart, no dropped request, no reconnect. Both numbers are
> Little's law to within 3%: 20 permits ÷ 2.5 s = 8, and 200 ÷ 2.5 s = 80.
>
> The more interesting half is where the extra throughput stopped. It did not go
> to 80/s. It went to **51.5/s**, because widening our own bulkhead handed the
> constraint to the *next* layer down — 3e's egress limiter, holding provider B
> to the 50 TPS it is contracted for. Bulkhead rejections went to zero and
> egress rejections took over.
>
> And a design that was in the first draft did not survive contact: the
> `config_version` column and the trigger that maintained it were removed, not
> because they failed, but because nothing consumed them.

---

## Hypothesis

Written before the runs.

> Everything phase 3 built reads its settings once, at startup, from one set of
> values shared by every provider. 3d showed that a static bulkhead sized from
> healthy latency turns a slow provider into a 93% decline rate, and 3e showed
> an egress limit is one provider's contract and cannot be shared. So the
> settings belong in `psp_config`, per provider, changeable while running.
>
> I expect the mechanical part to be dull — a poll, a comparison, a push — and
> the interesting part to be the circuit breaker, which resilience4j fixes at
> construction and which will therefore have to be rebuilt, losing its window.
>
> **Least confident about:** whether "without a restart" survives being measured.
> It is easy to demonstrate that a value in memory changed and much harder to
> show that the change reached the thing that actually governs a payment.

---

## Setup

| | |
|---|---|
| Providers | `psp-a` 99.9%/200 ms/500 TPS · `psp-b` 96%/2.5 s/50 TPS · `psp-c` 98%/800 ms/200 TPS |
| Simulators | three `mock-psp-simulator` containers with baseline personalities |
| Config | `psp_config`, polled by `psp-connector` every 2 s |
| Load | `ramp.js`, 100 rps, 25 s stages, routed to `psp-b` |
| Change | one `UPDATE` at t+70 s, nothing else |

The ingress limits were raised for this run so 3e's per-merchant ceiling was not
the binding constraint — the variable under test is a connector-side limit, and
a run where the edge shed the traffic first would measure nothing.

`mockpsp` keeps priority 10, its phase-1 settings and its unconfigured, healthy
simulator, so experiments 00 through 05 still reproduce exactly as written. The
new providers sit behind it and 3f steers traffic by changing `priority`, which
is a demonstration rather than a workaround.

---

## A. The three personalities are real

Three payments through the full stack, per provider, after warm-up. Routing was
changed between them by `UPDATE psp_config SET priority = ...` and nothing else -
the orchestrator reads that table per request, so it needed no restart either.

| | Simulator baseline | End-to-end p50 | Overhead |
|---|---|---|---|
| `psp-a` | 200 ms, 0.1% errors | **295 ms** | ~95 ms |
| `psp-b` | 2,500 ms, 4% errors | **2,600 ms** | ~100 ms |
| `psp-c` | 800 ms, 2% errors | **891 ms** | ~91 ms |

A constant ~95 ms of our own stack on top of whatever the provider does, which
is the shape you want: the system's cost does not scale with the provider's.

Their resilience configuration differs accordingly, and every number is derived
from that provider's own contract rather than copied:

```
psp-a  bulkhead=100/250ms   breaker=20%/30s/min50/open10s/probe5  retries=2  slice=500ms   egress=500/s
psp-b  bulkhead=125/1000ms  breaker=30%/60s/min20/open20s/probe3  retries=3  slice=6000ms  egress=50/s
psp-c  bulkhead=160/500ms   breaker=25%/30s/min30/open15s/probe5  retries=2  slice=2000ms  egress=200/s
```

Two of those are worth pausing on, because they invert the intuition a single
global setting encodes:

- **B needs more concurrency than A** — 125 against 100 — despite one tenth the
  throughput. Little's law tracks *latency*, not traffic, and that is exactly why
  3d's single global 20 was wrong for everyone.
- **B's breaker opens at 30% where A's opens at 20%**, because B fails 4% of the
  time when perfectly healthy. A threshold that does not clear a provider's own
  baseline flaps continuously — the trap the phase-3 notes call out, and one no
  shared value can avoid for two providers with different baselines.

---

## B. The change, mid-run

`psp-b`'s bulkhead was deliberately set to 20 — sized as though B were fast,
which is precisely the mistake 3d measured. One `UPDATE` at 11:15:13 raised it
to 200. Nothing else changed, and nothing was restarted.

| | Before (limit 20) | **After (limit 200)** |
|---|---|---|
| Bulkhead permitted | 8.0/s | **82.4/s** |
| Bulkhead rejected | 11.2/s | **0.0/s** |
| Egress permitted | 8.0/s | 51.5/s |
| Egress rejected | 0.0/s | **30.9/s** |
| **Requests reaching `psp-b`** | **8.0/s** | **51.4/s** |

Little's law predicts both sides and gets both right:

```
before   20 permits / 2.5 s = 8.0 rps      measured 8.0
after   200 permits / 2.5 s = 80.0 rps     measured 82.4
```

The change was visible in `/actuator/pspconfig` within 5 seconds of the
`UPDATE` - one poll interval plus rounding - and the connector logged it:

```
psp_config applied for 'psp-b': bulkhead=200/1000ms ... (was bulkhead=20/1000ms ...)
```

Over the whole run: 6,499 payments, 3,453 authorised, 3,046 declined, **zero
unknowns and zero transport failures**. The reconfiguration cost nothing in
correctness — no request was dropped, no connection reset, no payment left in an
ambiguous state. That is the part that a restart could not have offered.

### Where the throughput actually stopped

80/s was available and the system delivered 51.4/s. That gap is the result worth
keeping.

Widening the bulkhead did not remove the constraint, it **moved** it. Provider B
is contracted at 50 TPS, and 3e's egress limiter went from never firing (0/s
rejected, because the bulkhead was refusing everything first) to doing all the
work (30.9/s rejected). The system settled precisely on the contract.

This is the layers composing rather than overlapping, and it has a practical
consequence: **the effect of relaxing a limit is bounded by the next limit
down.** An operator widening a bulkhead during an incident gets throughput up to
the contracted rate and not one request beyond it — which is the correct amount
of help, and considerably safer than a change that would have taken us to 80/s
and had the provider throttle the account.

Worth noting what did *not* change: bulkhead rejections and egress rejections
both mean **nothing was sent**, so both are recorded `FAILED` rather than
`UNKNOWN`. The payment semantics are identical either side of the change. Only
the `errorCode` moved, from `bulkhead_full` to `provider_rate_limited` — which
is the distinction an operator needs and the caller does not.

---

## What the implementation cost

Three things were harder than expected, and all three are recorded in the code.

**The circuit breaker cannot be reconfigured, only rebuilt.** Resilience4j fixes
a breaker's configuration at construction, so changing a threshold means
discarding the instance — and its sliding window with it. An open breaker returns
to closed and the next `minimumNumberOfCalls` calls go out before it can form an
opinion again. That is a genuine hazard at the worst possible moment, since the
most likely time anyone edits a breaker threshold is during an incident in which
it is open.

It is still the right trade, because the alternative is a restart, which does the
same thing to the breaker *plus* the connection pools, the retry budgets and
every in-flight payment. The blast radius is one provider's breakers instead of
the process. `DynamicReconfigurationTest` asserts both halves — that identical
config is a no-op, and that a real change closes an open breaker — so the cost is
recorded in the build rather than only in a comment.

**A poll must not look like a change.** Because rebuilding a breaker resets its
window, a store that treated every read as a change would reset every breaker
every two seconds, and they would be permanently unable to accumulate enough
calls to open. The breaker would exist, report healthy, and never fire. So
`sameBehaviourAs` compares the fields the service actually acts on, and an edit
to `display_name` touches nothing.

**Shrinking a bulkhead is safe in a way that is not obvious.** Reducing a
semaphore's permits below the number currently held leaves it with a negative
balance, which resolves as in-flight calls finish. Nothing is interrupted and
nothing is over-admitted — a shrink takes effect as capacity is returned rather
than by seizing it.

---

## What did not survive

The first draft had a `config_version` column and a database trigger to bump it,
on the reasoning that an operator's hand-typed `UPDATE` should not have to
remember to signal a change. Both were removed.

The immediate reason was mechanical: MySQL refuses `CREATE TRIGGER` without
`SUPER` while binary logging is on (error 1419), so the trigger would have cost a
server-wide privilege loosening to exist.

The real reason is that nothing consumed it. Change detection compares the
behaviour fields — it has to, for the reason above — so the version was a signal
with no reader, maintained by machinery that needed a privilege escalation. What
replaced it is `ON UPDATE CURRENT_TIMESTAMP`, which the server maintains for
free and which answers the only question anyone asks of that column: when did
this last move.

A second thing the migration could not do: the `SELECT` grant for
`config_reader` cannot live in the Docker init script, because MySQL 8.4 refuses
a table-level grant on a table that does not exist and the init scripts run long
before Flyway creates `psp_config`. The account is created in init; the privilege
is granted in `V7`, where the table is guaranteed to exist.

---

## Credentials

`psp-connector` now holds **two** read-only database connections with disjoint
grants, and that is deliberate rather than incidental:

| Credential | Reaches | Cannot reach |
|---|---|---|
| `vault_reader` | `payorch_vault.token_vault` | anything in `payorch` |
| `config_reader` | `payorch.psp_config` | `payment`, `merchant`, `idempotency_record`, the vault |

"Read only" is not one permission. A credential that can read cards is not
interchangeable with one that can read timeouts, and the grant rather than any
code review is what guarantees the connector cannot read a payment row.

---

## What this changes

1. **3d's finding is now actionable.** A bulkhead sized from healthy latency is
   still a latent outage, but it is one an operator can correct in under a poll
   interval instead of one that needs a deploy.
2. **Relaxing a limit is bounded by the next limit.** Measured, not assumed: the
   bulkhead went to 200 and throughput stopped at the contracted 50 TPS. That is
   what makes widening a limit during an incident a safe action.
3. **Adding a provider is an `INSERT`.** `PspAdapterRegistry` resolves from rows,
   so the set of providers is no longer fixed when the context is built. Phase 5
   needs that before it can route on health.
4. **The config loop needs its own alert.** `payorch.psp.config.reload.failures`
   is the series to watch: when the poll fails the service keeps running on the
   last configuration it read, correctly and at full speed, so every other signal
   looks perfect while the system has quietly stopped accepting changes.
5. **`/actuator/pspconfig` reports the store's own view, not the database's.**
   Re-querying would answer the wrong question — an unapplied change and an
   applied one look identical from the database side.

---

## Reproducing

```bash
docker compose up -d --build          # a fresh volume is needed for V5-V7 and 02-config-reader.sql

# A. the three personalities
for port in 8086 8087 8088; do curl -s localhost:$port/_chaos; echo; done
docker exec payorch-mysql mysql -uroot -proot payorch \
  -e "UPDATE psp_config SET priority = CASE psp_id WHEN 'psp-b' THEN 1 ELSE 100 END;"

# B. the change, mid-run
docker exec payorch-mysql mysql -uroot -proot payorch \
  -e "UPDATE psp_config SET bulkhead_max_concurrent=20 WHERE psp_id='psp-b';"
RATELIMIT_MERCHANT_RPS=2000 RATELIMIT_WRITE_RPS=2000 docker compose up -d
MAX_RATE=100 STAGE_DURATION=25s bash tools/loadtest/run-experiment.sh 3f-01-dynamic-bulkhead ramp.js &
sleep 70
docker exec payorch-mysql mysql -uroot -proot payorch \
  -e "UPDATE psp_config SET bulkhead_max_concurrent=200 WHERE psp_id='psp-b';"

# what is actually in force, as opposed to what the database says
curl -s localhost:8083/actuator/pspconfig | python -m json.tool
```

The per-second rates in section B come from `config-change.csv` in the results
directory, captured alongside the run: `capture-metrics.sh` collects a fixed set
of series and the bulkhead and rate-limiter gauges are not among them, so
cumulative counters read after the run cannot be split either side of the change.
