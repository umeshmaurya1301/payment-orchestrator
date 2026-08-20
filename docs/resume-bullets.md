# Resume bullets, each traceable to a measured number

Phase 10's rule: written from the experiment writeups, not from adjectives. Not
*"built a resilient payment platform"* but a number, a mechanism, and how it was
measured.

Every figure below links to the page that produced it. **A bullet whose number
cannot be traced to a page is not on this list** — which is why the concurrency
and index work is in the second table rather than the first.

## Measured

| Bullet | Source |
|---|---|
| Cut unresolvable payments from **2,701 to 4** against a dead provider by adding a per-operation circuit breaker, while reducing load on the failing provider by **94%**; measured with k6 at 500 rps. | [03](experiments/03-circuit-breaker.md) |
| Eliminated edge OOM under load — **1 OOM to 0**, **5,858 unanswered requests to 0**, p99 **22.5 s to 3.2 s** — with a three-layer rate limiter, after a bulkhead alone failed to. | [05](experiments/05-rate-limiters.md) |
| Held end-user success at **91.2%** (from 97.5%) while a provider degraded to 80% errors, by driving routing weight from live health signals; static priority routing collapsed to **4.2%** on the same fault, and the shift completes in **7 s**. | [09](experiments/09-health-routing.md) |
| Closed a permanent event-loss window measured at **20 of 60 payments** during a 30-second broker outage — every one `AUTHORIZED`, every one a `201`, no outage visible — using a transactional outbox; CDC relay p50 **5 ms** against polling's **260 ms**. | [10](experiments/10-outbox.md) |
| Converted **2,112 payments stranded in `AUTHORIZING`** into **2,372 recorded `UNKNOWN`** against a provider that never answers, raising throughput **60%**, by propagating a deadline budget across every hop. | [01](experiments/01-deadline-budget.md) |
| Capped retry amplification at **10% of traffic** with a token-bucket retry budget, accepting **67%** success against uncapped retry's **94%** in exchange for **12%** extra load on a failing provider instead of **54%**. | [02](experiments/02-retry.md) |
| Cut log volume **97.6%** (from 75 MB/minute at 500 rps) with trace-based sampling while retaining **100% of error lines** — 2,325 lines against 2,315 failed payments. | [08](experiments/08-log-sampling.md) |
| Found that **three of four production alerts could not fire**, including one measuring HTTP 5xx that stayed at **zero while 99.7% of payments failed**, because a decline is a `201` — and that no payment-outcome metric existed at all. | [07](experiments/07-alerts.md) |
| Raised provider throughput **8.0/s to 51.4/s** with a single mid-run `UPDATE` and no restart, with both sides of Little's law agreeing to within **3%**. | [06](experiments/06-dynamic-config.md) |
| Diagnosed **1,911,000 minor units** of ledger drift caused by a read-modify-write lost update, invisible to two independent balance invariants that were green throughout; replaced with an atomic SQL delta and added a drift check. | [15](experiments/15-capture-and-drift.md) |
| Traced a payment across five services and an async webhook in **one distributed trace** after finding the ledger appeared in **0 spans and 0 log lines** of the trace that caused it — the fix required capturing trace context as a database column, not enabling instrumentation. | [12](experiments/12-trace-propagation.md) |
| Demonstrated a webhook receiver accepting **7 of 7 forged deliveries** including a fabricated `AUTHORIZED` for **INR 500,000**, then closed it with HMAC signing over a timestamped payload. | [13](experiments/13-webhook-security.md) |

## Built and unit-tested, not yet measured on a live stack

Honest separation, because these have no experiment page with numbers behind
them yet. They are defensible as design, not as measurement.

| Bullet | Evidence |
|---|---|
| Designed envelope encryption for a card vault so a key-encryption key rotates by re-wrapping **48 bytes per row**, rewriting **no card ciphertext** — asserted byte-for-byte in test. | [ADR 0007](adr/0007-tokenization-boundary.md), `VaultRotationTest` |
| Implemented crypto-shredding for right-to-erasure: destroying a merchant's key material renders their card data unrecoverable **in every copy including backups**, with no row deleted — verified against a simulated restore. | phase 9c, `VaultRotationTest` |
| Found and fixed a graceful shutdown that could never complete — Spring's 30 s drain against Docker's 10 s default grace period, on requests permitted to run 30 s. | phase 7i |
| Measured that `synchronized` no longer pins carrier threads on JDK 25 (JEP 491) — **106 ms against the ~900 ms** pinning would cost — correcting advice this project had itself been repeating. | phase 7g, `LockFreePrimitivesTest` |

## What is deliberately not here

- **"Reduced latency by X%"** with no page behind it.
- The bulkhead. It is in the project and in [ADR 0002](adr/0002-semaphore-bulkhead.md),
  and the experiment did not justify it: success against a merely *slow* provider
  fell from **100% to 6.8%**. A bullet claiming it as a win would be the kind of
  claim that does not survive one follow-up question.
