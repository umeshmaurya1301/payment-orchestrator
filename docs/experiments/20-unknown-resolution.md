# 20 — The reconciliation that could not conclude

**Phase 8a.** `UnknownResolver`, `StatusFanout`

---

## Hypothesis

Phase 3a built the `UNKNOWN` state so that a connector timeout would never be
mistaken for a decline: the provider may have authorized the card and lost the
response, and calling that `FAILED` is how a caller is invited to retry into a
double charge. The cost of that decision is a state nothing resolves on its own,
and phase 8a's poller is what resolves it — ask every provider whether they hold
the reference, and act on the answer.

The poller was built in 8a with 20 unit tests, most of them about the answers it
must **refuse** to act on. It had never been run against a live stack. The
prediction was simply that enabling it would drain the backlog.

There were 11,601 `UNKNOWN` payments accumulated across the whole project, the
oldest 373,221 seconds — **4.3 days** — old.

## Setup

```
UNKNOWN_POLLER_ENABLED=true UNKNOWN_POLLER_INTERVAL_MS=5000 \
UNKNOWN_POLLER_BATCH_SIZE=50 UNKNOWN_POLLER_BASE_BACKOFF_MS=5000 \
docker compose --profile async up -d payment-orchestrator
```

## Actual result

### It resolved nothing, and said so precisely

```
payorch_recon_polled_total                    250
payorch_recon_inconclusive_total              250     <- every single one
payorch_recon_resolved_authorized_total         0
payorch_recon_resolved_failed_total             0
payorch_recon_gave_up_total                     0
payorch_payments_unknown                   11,601     <- unmoved
payorch_payments_unknown_oldest_age_seconds  373,221
```

Every poll came back **inconclusive**, and the fan-out explained why:

```json
{ "answered": [], "silent": ["mockpsp", "psp-a", "psp-b", "psp-c"] }
```

All four providers silent — while the same reference, fetched directly from a
provider with `curl`, returned **HTTP 200**. No breaker was open. No bulkhead was
rejecting. No deadline was declining. The fan-out completed in **2 ms**.

That behaviour is the resolver being **correct**. `silent` is the field that
decides whether "nobody has it" is safe to conclude, and a provider that did not
answer is not a provider that said no. Concluding `FAILED` from silence is
exactly the double-charge reasoning the `UNKNOWN` state exists to prevent. So it
refused, correctly, forever.

### Three bugs, stacked

**1. The fan-out discarded the reason.** `askEveryone` did this:

```java
scope.join().forEach(subtask -> {
    if (subtask.state() == Subtask.State.SUCCESS) { ... }
});   // FAILED subtasks: dropped, silently
```

`subtask.exception()` was never read. The class that exists to distinguish "said
no" from "said nothing" was itself silent about *its own* silence — so there was
no log line anywhere naming the cause, at any level, and diagnosis had to proceed
by elimination. Logging the exception type was the change that made the next two
findable.

**2. The adapter deserialized a shape the provider has never returned.**

```
LookupResponse   { providerRef, reference, outcome, amountMinor, captured, reversed }
what it returns  { reference, authorizations: [ ... ] }
```

`/psp/v1/references/{reference}` returns a **list**, deliberately: one reference
carrying two authorizations *is* a double charge, and a `providerRef`-keyed
lookup can never show it. That is written down in `ProviderApi.ReferenceView`
and dates from phase 1.

Jackson threw on the unknown property, `RestClient` wrapped it as
`RestClientException`, and the adapter turned that into
`ProviderUnavailableException` — the exception meaning *"the provider did not
answer"*. A provider answering correctly, in 2 ms, with HTTP 200, was recorded
as unreachable.

**3. `RedisLock` had never been created in any service.** Before any of the
above, enabling the poller made the orchestrator refuse to start:

```
Parameter 2 of constructor in UnknownResolver required a bean of type
'com.payorch.infra.resilience.lock.RedisLock' that could not be found.
```

on a service with `spring-boot-starter-data-redis` on the classpath and
`SPRING_DATA_REDIS_HOST` set. The bean is `@ConditionalOnBean(StringRedisTemplate.class)`
— the right condition — inside an `@AutoConfiguration` with no ordering.
`@ConditionalOnBean` is evaluated against beans registered *so far*, so an
auto-configuration that runs before Redis's own sees nothing and the condition
silently does not match. Phase 7h's distributed lock had never existed at
runtime, in any service, since the day it was written.

Fixed with `afterName = "...DataRedisAutoConfiguration"` — `afterName` so the
starter needs no compile dependency on Redis, and `DataRedisAutoConfiguration`
because Boot 4 renamed it.

### After

```
  t+20s   UNKNOWN=11,601   polled=850    inconclusive=750   failed=0
  t+40s   UNKNOWN=11,551   polled=900    inconclusive=750   failed=50
  t+60s   UNKNOWN=11,501   polled=950    inconclusive=750   failed=100
  t+120s  UNKNOWN=11,401   polled=1050   inconclusive=750   failed=200
  t+160s  UNKNOWN=11,351   polled=1100   inconclusive=750   failed=250
```

Fifty per tick, and `inconclusive` flat at its pre-fix value — no *new*
inconclusive results at all. The backlog drains.

These resolve to `FAILED` rather than `AUTHORIZED` because the mock providers
hold their authorizations in memory and have been restarted many times since
those payments were made; every provider genuinely answers "I have never seen
this reference", which is a definitive answer and the correct resolution. A
freshly created reference resolves the other way — the manual lookup above
returned `claimedBy: psp-b, outcome: APPROVED`.

## What surprised me

**Every one of the three bugs was invisible by construction.** A silently
dropped exception, a condition that silently does not match, and a
deserialization failure disguised as an unreachable provider. None produced an
error, a warning, or a failed test. The system reported `inconclusive` — an
honest, correct, well-named answer — 250 times in a row, and that was the only
symptom.

**The resolver's caution is what made the bug survivable and what made it
invisible.** Had it concluded `FAILED` from silence it would have been *wrong*
and *obvious*: 11,601 payments marked failed, some of them real charges. Instead
it did the right thing and did nothing, and doing nothing looks the same whether
the providers are genuinely unreachable or the client cannot parse them.

**A component can be conditional on something that is never there yet.**
`@ConditionalOnBean` reads as a runtime check and is a registration-order check.
It is the fourth "present and inert" of this project and the most disquieting,
because the guarded component is the one whose javadoc argues hardest about
failing loudly rather than degrading silently — and it degraded so silently it
was never constructed at all.

**Nothing tested the lookup path end to end.** 20 unit tests covered what the
resolver should refuse to act on and every one passed against a mocked adapter.
The contract between the adapter and the provider — a list where a record was
expected — is exactly what a unit test with a mock cannot see, and it had been
wrong since the endpoint was written.

## Standing questions

- **The alert half of the criterion is not done.** `payorch.payments.unknown` and
  `payorch.payments.unknown.oldest_age_seconds` exist and are correct; no SigNoz
  rule queries them. The machinery from experiment 14 applies directly.
- **`resolved_authorized` is still zero.** Everything in this backlog predates
  the providers' current memory. The `UNKNOWN → AUTHORIZED` path is exercised by
  the manual lookup and not yet by the poller, and it is the more important of
  the two.
- **A reference with two authorizations is unhandled.** The adapter now takes the
  first and the comment says why that is a stopgap: two authorizations for one
  reference is a double charge, which a status lookup cannot resolve and the
  recon report should raise.
- **11,601 at 50 a tick is about an hour.** Fine here; a real backlog after a
  provider incident would want the batch size and interval reconsidered, and
  `payorch_recon_polled_total` is the number to size them from.
