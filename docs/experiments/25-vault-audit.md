# 25 — Who read the card

**Phase 9c.** `tools/security/vault-audit.sh`, `VaultAccessLog`, `04-vault-audit.sql`

---

## Hypothesis

Phase 9b's answer to "is card data safe" is envelope encryption, per-record DEKs
and a rotatable KEK. It is a good answer to the question it addresses — *can
somebody who steals this table read it* — and it does not touch the question an
auditor asks first:

> Who looked at this card, and why?

Nothing built so far can answer that. The reader in question holds a legitimate
credential and is doing exactly what it is authorised to do. **A service
authorised to detokenize once per authorization, doing it a million times on a
Tuesday night, is invisible to every control in this project.** The grants say
`psp-connector` may read cards; they say nothing about how often, for which
payments, or whether last week's spike was a batch job or an export.

Three claims to test, and "we have an audit log" is not one of them:

1. Only `psp-connector` can read a card, enforced at the credential boundary
   rather than in application code.
2. **Every** detokenization during a load run appears in the log — not most, not
   the ones on the happy path.
3. The audited service can append to its trail and cannot read, alter or erase
   it.

## Setup

```
tools/security/vault-audit.sh          # against the live stack
./gradlew -p infra-core :tokenization-starter:test --tests '*VaultAccessLogTest'
```

Claim 2 is the one that needs load. An audit log verified with a single manual
request proves the `INSERT` compiles. What it cannot show is whether some branch
— a retry, a breaker trip, a token that does not exist — reaches the card without
passing the recorder. Those branches are where a real audit gap lives and they
only execute under volume.

The completeness assertion is **equality**, not "at least one". A log containing
some of the reads is not an audit trail, and `>=` would pass a run where half the
branches skipped the recorder.

## Actual result

```
 1. THE CREDENTIAL BOUNDARY
   ok   payment-orchestrator reading a card                    denied
   ok   the auditor reading a card                             denied
   ok   psp-connector reading its own audit trail              denied
   ok   psp-connector erasing an audit row                     denied
   ok   psp-connector rewriting an audit row                   denied
   ok   psp-connector planting a card                          denied

 2. COMPLETENESS UNDER LOAD
   payments authorized                                       40
   SUCCESS rows in the window                                40
   ok   every authorization produced exactly one audit row     40
   distinct references recorded: 40
   rows with a correlation id:   40
   rows with a trace id:         40

 3. THE ROWS THAT MATTER ARE THE FAILURES
   ok   the failed lookup was recorded too                      1

 4. WHAT AN INVESTIGATION ACTUALLY RUNS
   ok   no PAN anywhere in the audit log                        0
```

Every denial is `ERROR 1142` from MySQL — asserted on the error *number*, because
"connection refused" and "no such table" would also produce an error and would
mean the control was never exercised.

One row, in full:

```
            id: 162
         token: tok_does_not_exist_at_all
         actor: psp-connector
       purpose: authorize
     reference: 00000000-0000-7000-8000-00000000dead
correlation_id: 395b43af-6c6a-4e22-9641-4697b550e006
      trace_id: fdb0e54a0bdec18242437f9c81245523
       outcome: UNKNOWN_TOKEN
```

A card lookup for a token that was never issued, attributable to a service, a
payment, a request and a trace. Nothing else in this system records that: the
connector answers 400 and the orchestrator records a rejected payment, and
neither says *a card lookup was attempted*.

## What surprised me

**Turning fail-closed on broke every test in the connector, and that was the
design working.** `AuthorizationFlowTest` went from green to four failures, all
`500`, the moment the audit log was enabled — because its H2 vault had no
`vault_access_log` table, so the access could not be recorded, so **no card was
disclosed**. I had written the fail-closed argument in a javadoc an hour earlier
and still spent a minute reading the stack trace as a bug. The behaviour showing
up in the least convenient place is exactly where a control like this is supposed
to show up; a control that only fires in the demo is a demo.

**The uncomfortable part of any audit trail is that the audited service writes
it.** There is no honest way around that without an out-of-process interceptor,
and pretending otherwise would be worse than saying it. What *can* be arranged is
that the record is beyond the writer's reach once written: `vault_reader` holds
`INSERT` and nothing else — no `UPDATE`, no `DELETE`, and deliberately **no
`SELECT`**. A compromised connector can append noise to its own trail; it cannot
erase an entry, alter one, or read back what has been recorded about it to
discover what an investigator would see. Append-only is not tamper-proof. It is
tamper-*evident*, which is the property that survives contact with an incident.

**The auditor credential cannot read cards, and that mattered more than expected
once written down.** `vault_auditor` has `SELECT` on the log and no grant at all
on `token_vault`. The account used to investigate card access cannot itself
access cards — otherwise investigating a breach requires handing somebody the
thing they are investigating.

**Fail-closed is a real cost and the argument for it is about correlation, not
purity.** It puts a second write in front of every decryption and turns an
audit-table problem into a payment outage. It is chosen because the alternative
fails in a worse place: the conditions that break the audit write — load, an
exhausted pool, a table nobody migrated — are correlated with the conditions
worth investigating, so a log skipped when writing it is inconvenient has its gap
*exactly where the incident is*. "We could not record who read this, so nobody
read it" is a defensible sentence. "We could not record it so we did it anyway"
is not. `fail-closed=false` exists as the control arm and
`failOpenReturnsTheCardAndLosesTheRecord` documents what it costs.

**The script called all six access controls broken while all six were working.**
`set -o pipefail` was on, MySQL exits non-zero when it denies a query, and in
`as_user ... | grep -q "ERROR 1142"` that failure outranks grep's success — so
every denial reported "no match", which the script rendered as **ALLOWED**. Six
security controls reported as absent, in a script whose entire purpose is
verifying they are present. Third time this session a measurement script has been
wrong about the thing it measured (experiment 19's load generator, 23's rate
limiter, now this), and the first where the false reading was alarming rather
than flattering — which is the only reason it got investigated in ten seconds
rather than published.

**The connector is on 8083.** The probe pointed at 8082, got a healthy `200` from
`/actuator/health` — a *different service* answering — and a 404 from the
endpoint it actually wanted. A health check that passes against the wrong process
is worse than one that fails.

## Standing questions

- **No alerting on the log.** The rows exist and nothing reads them. The obvious
  first alert is a run of `UNKNOWN_TOKEN` from one actor, and phase 8's finding
  applies: the thing worth paging on is not the thing that is easy to count.
- **Only `authorize` is instrumented.** It is the only path that detokenizes
  today, so the log is complete — but `purpose` has one value, and a report
  grouped by it currently proves nothing.
- **No retention on the audit log itself.** It grows forever, and it is the one
  table in the vault schema with no erasure story. An audit record naming a
  merchant's payment arguably survives that merchant's erasure request; that is a
  legal question this project has not answered.
- **The out-of-process interceptor is the honest version.** A proxy or a database
  audit plugin recording reads independently of the reader would remove the
  self-reporting limitation entirely. Not built, and named here so the limitation
  is a stated one rather than an implied claim.
