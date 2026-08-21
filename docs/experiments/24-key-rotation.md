# 24 — Rotating a credential without an outage

**Phase 9b.** `tools/security/rotate-api-key.sh`, `V16__api_key_rotation.sql`, `ApiKeyRotationTest`

---

## Hypothesis

Phase 9's exit criterion reads *"API keys hashed at rest, rotation demonstrated"*,
and half of it was already true. Keys have been SHA-256 hashed since V2 in phase
1, and `docs/experiments` has never contained a run where a database dump yielded
a usable credential. Ticking the criterion on that basis would have been
defensible and wrong, because **hashing at rest is not what limits exposure for
an API key.**

A merchant had exactly one key, stored in one column. Replacing it is therefore a
simultaneous edit on two sides — ours and theirs — and every honest version of
that procedure has a window in which their requests fail. So the procedure is
performed rarely. So keys live for years: in CI logs, on a laptop that left with
an employee, in a screenshot attached to a support ticket. The hash protects
against one specific attack (someone reads the table) and does nothing about the
one that actually happens (the key leaks somewhere else and nobody dares replace
it).

The prediction: with several keys per merchant and an overlap window, a key can
be replaced **with zero failed requests**, and the operator can *see* rather than
guess when the old one is safe to revoke.

## Setup

The measurement has to run against traffic. A rotation script executed on an idle
system demonstrates replacement, not rotation — it is precisely the outage this
feature removes, performed quickly enough that nobody noticed. So a client sends
a request every 300ms throughout, every response is counted, and the client
switches keys *mid-run* at a moment the script does not control.

```
tools/security/rotate-api-key.sh
```

The five steps are the ones an operator performs, in order: issue the new key →
retire the old one with an expiry → the merchant deploys the new key → check
`last_used_at` → let the window close.

The schema is one row per key rather than a second column, because two columns
encodes exactly two keys and the states are not symmetric. A rotation needs a key
that is *accepted but no longer advertised*, which is a status, not a slot:

| status | accepted? | meaning |
|---|---|---|
| `ACTIVE` | yes | the key the merchant is told to use |
| `RETIRING` | yes, until `expires_at` | the overlap window |
| `REVOKED` | no | kept, because deleting the evidence a key existed is the opposite of an audit trail |

## Actual result

```
 BEFORE
   ok   the existing key authenticates                       404
   ok   the not-yet-issued key does not                      401

 STEP 1  issue the new key
   ok   the new key authenticates immediately                404
   ok   the old key is untouched                             404

 STEP 2  retire the old key, with a deadline on the window
   ok   the retired key still authenticates                  404

 STEP 3  the merchant deploys the new key, at a time they chose
   ok   still no failed request                              0

 STEP 5  the window closes by itself
   ok   the expired key is refused                           401
   ok   the new key is unaffected                            404
   ok   the row was NOT flipped by any job                   RETIRING

 RESULT
   requests during the whole rotation                        53
   rejected with 401                                         0
```

**53 requests, 0 rejected**, across issue, retire, client switchover and expiry.

Verified live afterwards against MySQL rather than only H2: V16 applied, both
seeded merchants backfilled, `merchant.api_key_hash` dropped, a real payment
returned `201 AUTHORIZED`, a bogus key returned `401`, and `last_used_at` was
stamped for the merchant that called and left `NULL` for the one that did not.

## What surprised me

**The expiry has to be enforced on the read path, and I nearly built it as a
job.** The obvious design is a scheduled task that flips `RETIRING` to `REVOKED`
when `expires_at` passes. It is also the design where an overlap window silently
becomes permanent: that job is exactly the kind nobody notices has stopped, and
the failure is invisible because everything keeps working — there are simply
twice as many live credentials as anyone believes. Reading `expires_at` on every
authentication closes the window whether or not anything is running, which the
script asserts directly:

```
   ok   the expired key is refused                           401
   ok   the row was NOT flipped by any job                   RETIRING
```

The row still says `RETIRING`. Nothing flipped it. The key is refused anyway.

**`last_used_at` puts a write on the authentication path, and the naive version
costs more than the feature.** The column is the point of the whole table —
without it, revoking means asserting nobody is still using the old key, which is
a guess that takes down a merchant's integration during their business hours. But
writing it on every authenticated request means, by experiment 23's own numbers,
~279 writes/s to MySQL to maintain a timestamp whose only consumer is a human
deciding whether to revoke a key. That person cannot use a value accurate to the
second any differently from one accurate to the minute. So it is throttled to one
write per key per instance per minute.

**Which creates a caveat the demo walked straight into.** The run printed:

```
   --   old key last used at    2026-08-21 15:16:04.537
   --   quiet for               7s
```

Seven seconds of quiet — while the key had been actively used moments earlier and
the stamp simply had not been refreshed yet. **The staleness of `last_used_at`
sets a floor on how long you must observe quiet before trusting it**, and that
floor is the throttle interval, not zero. Reading "quiet for 30s" off a 60s
throttle tells you nothing at all. In production the threshold is hours or days
so the floor is irrelevant, but the number is misleading precisely in the regime
where somebody is impatient — which is the regime where they will read it.

**A rejected key must not be stamped, and the reason only became obvious after
writing the test.** If failed authentications refreshed `last_used_at`, then a
revoked key being hammered by a stale client — or by an attacker — would keep
reporting itself as "in active use", forever, and most loudly exactly when it is
being abused. The signal would invert.

**Dropping the old column in the same migration was the uncomfortable call and is
the right one.** Leaving `merchant.api_key_hash` in place would have made the
migration reversible. It would also create two places a credential is checked
against, which is how a revoked key keeps working: revoke it in the new table,
and the stale column still authenticates. Two sources of truth for an
authentication decision is a security bug with a deprecation notice on it. The
cost is honest — V16 cannot be rolled back without the plaintext keys, which
nobody has, because that is the entire idea.

**The script's own first run reported a pass as a failure.** `grep -c` prints `0`
*and* exits 1 when it matches nothing, so `grep -c '^401$' || echo 0` appended a
second zero and every comparison ran against `"0\n0"`. The rotation had worked
perfectly and the summary said *"the rotation dropped requests: 0"*. Harmless
here because it failed in the safe direction, and worth recording next to
experiment 23, where the same class of bug — a script measuring something other
than what it claimed — failed in the flattering direction instead and nearly got
published.

## Standing questions

- **Nothing issues keys.** The script inserts a row with `SHA2()` in SQL. There
  is no endpoint, no admin authentication, and no place a merchant sees the
  plaintext exactly once. That is a deliberate scope choice — an unauthenticated
  admin API would be a larger security hole than the one being closed — but it
  means "rotation" here is an operator procedure, not a self-service feature.
- **Key scopes do not exist.** Every key can do everything its merchant can. The
  phase mentions scopes; this does not implement them, and a read-only key for a
  reporting integration is the obvious next thing.
- **The overlap window is not enforced to be short.** `expires_at` is whatever
  the operator typed. Nothing rejects a ten-year window, and nothing alerts on a
  `RETIRING` key that has been retiring for a month.
- **No metric on retired-key usage.** The filter logs a warning when a
  revoked or expired key is presented — the only signal that a rotation was
  completed too early — but it is a log line, not a counter, so it cannot be
  alerted on. Phase 8's own finding about DLQ depth applies: the thing worth
  paging on is not the thing that is easy to count.
