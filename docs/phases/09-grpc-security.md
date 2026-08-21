# Phase 9 — gRPC migration, security and data protection

| | |
|---|---|
| **Estimate** | ~3 weeks |
| **Depends on** | phases 1, 3 and 6 |
| **Delivers** | REST vs gRPC benchmark, Vault-backed envelope encryption, and the full PCI story |

## Goal

Migrate the internal transport, and turn "we protect card data" into four
answerable questions: where does raw PAN exist, how is it protected, who can
reverse it, and how does erasure work.

## Why here

The gRPC migration is only interesting because REST came first (phase 1) and ran
under the same load harness (phase 2) — that is what makes the benchmark a data
point rather than an opinion.

The data-protection work comes last because it hardens things that must already
exist: the token vault (phase 1), webhook HMAC (phase 6), and API keys (phase 1).

---

## 9a. gRPC migration

### Implementation

1. **Protobuf definitions** in a shared `proto/` module.
2. **Migrate orchestrator ↔ router ↔ connector** from REST to gRPC.
3. **Deadline propagation over gRPC metadata**, replacing phase 3a's header
   scheme. gRPC has first-class deadlines that propagate automatically — this is
   the version of the deadline budget that does not need hand-rolling, and being
   able to compare the two implementations is why 3a built it by hand.
4. **Server streaming** for live payment status.
5. **gRPC status → HTTP status mapping** at the edge. Decide the table
   deliberately: `DEADLINE_EXCEEDED` → 504, `RESOURCE_EXHAUSTED` → 429,
   `FAILED_PRECONDITION` vs `ABORTED` → 409/422.
6. **Schema evolution notes:** field numbering, `reserved` fields, and what
   actually breaks. Removing a field without reserving its number and then
   reusing that number is the classic silent data-corruption bug.
7. **REST vs gRPC benchmark under identical load.**

### Exit criteria

- [ ] All three internal hops on gRPC
- [x] Deadlines propagate over metadata; a short deadline fails fast at the
      connector — 9a, verified live 2026-08-21. With the provider slowed to
      4,000ms:

      | edge budget | result | elapsed |
      |---|---|---|
      | 30,000ms | `AUTHORIZED` | 4.19s — the latency propagated and fitted |
      | 2,000ms | **`UNKNOWN`** | **2.11s** — cut off at the budget, not the provider |

      The answer is the one that matters: *"could not be confirmed within the
      time budget. It may or may not have been processed"* — phase 3a's
      `UNKNOWN` semantics preserved across the transport swap. `withDeadlineAfter`
      reads the same `ScopedValue` the REST client reads, so the two arms take
      their budget from one source.

      A false start worth recording: the first attempt set
      `DEADLINE_BUDGET_MS=2000` on the **orchestrator** and the call still took
      4.5s and succeeded. The orchestrator trusts the inbound header, so the
      edge's 30s budget won — the budget has to be cut where it originates, and
      a test that sets it one hop too late measures nothing
- [ ] Server streaming works for status
- [x] gRPC → HTTP status mapping documented and tested — 9a.
      Documented as [ADR 0009](../adr/0009-grpc-status-mapping.md), with the
      four options that lost; tested in both directions over in-process gRPC
      rather than mocked stubs, so a status has to survive the real serializer:

      | Cause | status | description | client throws | payment |
      |---|---|---|---|---|
      | breaker open | `UNAVAILABLE` | `circuit_open` | Rejected | `FAILED` |
      | bulkhead full | `UNAVAILABLE` | `bulkhead_full` | Rejected | `FAILED` |
      | egress limited | `RESOURCE_EXHAUSTED` | `provider_rate_limited` | Rejected | `FAILED` |
      | no answer | `UNAVAILABLE` | **`provider_unavailable`** | Unavailable | **`UNKNOWN`** |
      | we gave up | `DEADLINE_EXCEEDED` | any | Unavailable | **`UNKNOWN`** |
      | our own bug | `INTERNAL` | `internal_error` | Unavailable | **`UNKNOWN`** |
      | anything else | any | any | Unavailable | **`UNKNOWN`** |

      21 tests across `ConnectorGrpcStatusMappingTest` and
      `GrpcStatusTranslationTest`. Verified non-vacuous by mutation: flipping
      `DEADLINE_EXCEEDED` to Rejected fails exactly one test, the one that says
      *"the request may well have reached a provider before we gave up"*.

      Two things this found. `ConnectorRejectedException` had said *"its circuit
      is open"* since phase 3 and is thrown by four different gates — a message
      naming the wrong subsystem sends an operator to inspect a breaker that is
      closed, so it now names the gate that actually refused. And a **bare**
      `UNAVAILABLE` with no description maps to `FAILED`, which is the one place
      the safe-side rule is knowingly not applied; it is right for a refused
      connection and wrong if a proxy ever rewrites a real timeout. That is
      recorded in the ADR and pinned by a test rather than left to be discovered
- [x] **Benchmark written up** — throughput, P99, payload size, CPU —
      [experiment 23](../experiments/23-rest-vs-grpc.md), two independent runs
      agreeing to within 1%:

      | | REST | gRPC |
      |---|---|---|
      | payments in 45s | 12,580 / 12,657 | **14,141 / 14,298** (+13%) |
      | p95 | 156.7 / 153.5ms | **129.8 / 127.7ms** (−17%) |
      | mean CPU | 559 / 583% | 569 / 585% (equal) |
      | `AuthorizeRequest` | 185 bytes | **97 bytes** (−48%) |
      | `AuthorizeResponse` | 101 bytes | **37 bytes** (−63%) |

      Payload sizes come from `PayloadSizeTest` rather than the script — the one
      number of the four that is deterministic, so it is re-checked on every
      build. P95 not P99: k6's summary carries p90/p95 and a p99 over 12,000
      samples is thin. That is a substitution, not a detail.

      **The first two runs were invalid and passed anyway.** They reported the
      two arms 0.1% apart, which read as confirmation of the hypothesis and was
      in fact `merchant-per-sec: 50` — 99.5% of requests rejected by a token
      bucket three services upstream, so the p95 being compared was the p95 of
      *rejections*. The script now asserts that at least half the requests
      reached a connector, separately from the comparison it makes

### Traps

**Benchmarking gRPC against unoptimised REST.** Use connection pooling and
keep-alive on both, or you are measuring TCP handshakes.

**Assuming gRPC is always faster.** For small payloads over a warm connection the
gap is often smaller than expected. Report what you measure, including if it is
unflattering — that is more credible, not less.

**Losing trace context.** It does not propagate across gRPC for free.

---

## 9b. Encryption and key management

### Implementation

1. **HashiCorp Vault** container. Run it locally so key management is real rather
   than hand-waved.
2. **Envelope encryption** for data at rest: a per-record **DEK**, wrapped by a
   **KEK** held in Vault. Applies to the `token_vault` mapping table and to raw
   PSP request/response payloads in Mongo.
3. **Key rotation:** rotate the KEK **without re-encrypting every record**.

   This is the whole point of the envelope scheme and the thing to be able to
   explain: each record stores its own DEK, encrypted under the KEK. Rotating the
   KEK means decrypting and re-encrypting *the wrapped DEKs* — small, fixed-size
   values — not the records themselves. Rotating a directly-applied key would
   mean rewriting every row.
4. **mTLS between services.**
5. **API-key hashing and rotation**; webhook HMAC signing with timestamp replay
   protection (built phase 6, hardened here).

Your existing `Infra-Core` repo's `infra-cryptography` module already has
AES-GCM, AES-CBC-HMAC, RSA, Argon2 and an `HmacService` — a substantial head
start worth lifting rather than rewriting.

#### 9b, as built (envelope + rotation; Vault container still outstanding)

**What envelope encryption buys is not secrecy.** `PanCipher` was already
AES-256-GCM with a per-row nonce and the token as AAD; nothing here makes the
ciphertext harder to break. What it makes possible is **rotating the key without
rewriting the data** — and that difference is structural rather than
cryptographic. With one key applied directly, rotation *is* a full re-encryption:
a long, stateful, resumable job holding plaintext PANs in memory and touching the
most sensitive table in the system. Which is why, in practice, systems built that
way do not rotate until an incident forces them to.

**The claim is asserted, not asserted-in-prose.**
`VaultRotationTest.aKekRotationRewritesNoCardCiphertextAndLosesNoCard` rotates a
populated vault and compares every stored `pan_iv || pan_cipher` before and
after: **byte-identical**. If any card had been rewritten, the rotation would
have decrypted it and the whole argument would be hollow.

**`rewrap` takes a wrapped key and returns a wrapped key.** It has no parameter
through which a PAN could reach it, so "rotation never decrypts a card" is
enforced by the signature rather than promised by a comment. The rotation job
never selects `pan_cipher` at all.

**Per-record DEKs, not one for the table.** A table-wide DEK would also make
rotation cheap and would hand back what the scheme buys: one compromised key
exposing every card. Per-record keys bound the blast radius to one card — and
they are what makes 9c's crypto-shredding work, since destroying one record's key
destroys one record's data. The cost is 44 bytes per row; that is the entire
trade.

**Rotation has no cutover.** Each record names the KEK version that wrapped it,
so the ring gains a version and the re-wrap job catches up over hours with no
window in which the vault is partly unreadable.
`theVaultStaysReadableWhileTheRotationIsOnlyPartlyDone` rotates one row at a time
and re-reads every card after each. That property is what makes a key rotation
*boring*, which is the highest praise available for one.

**The append-only grant collided with rotation, and column-level grants resolved
it.** `01-vault.sql` said, correctly, that there is no `UPDATE` for anyone —
"no code path can quietly rewrite the card behind an existing token". Rotation
needs `UPDATE`. Granting it on the table gives away the property that was the
point; moving DEKs to a second table adds a join to the detokenization path to
work around a permissions problem. Instead `vault_rotator` holds
`UPDATE (wrapped_dek, dek_iv, kek_version)` and nothing else: **structurally
incapable of altering a card**, whatever it is asked to do.

**The one migration this scheme cannot make cheap is the migration into it.**
Pre-9b rows were encrypted directly under the static key and have no DEK to
re-wrap. `kek_version IS NULL` marks them, `PanCipher` is retained solely to read
them, and `VaultRotation` *counts* them rather than skipping them silently —
because the obvious predicate `WHERE kek_version <> :current` is UNKNOWN for a
NULL in SQL's three-valued logic, so every legacy row would be quietly excluded
by a job reporting success.

**What is NOT done, stated rather than implied.** The KEK is **local key material
in configuration**, not held in Vault or a KMS. A real deployment performs the
unwrap *inside* the key store so the KEK never enters this process — which is the
property that makes a memory dump of the service uninteresting. That needs the
Vault container and is outstanding. What is *not* deferred is the design: the
schema, the per-record DEKs, the version-naming and the rotation are the
expensive things to retrofit, and they are real. Swapping wrap/unwrap for a Vault
call is a change to two methods; changing a schema that assumed one static key is
a change to every row.

### Exit criteria

- [~] Vault-backed envelope encryption on the token vault — 9b. Envelope
      encryption is built, wired and tested; the KEK is **local key material,
      not Vault-held**, and the Vault container is outstanding. Said plainly
      because the phase's own trap list asks for it
- [x] **A demonstrated KEK rotation** — old records still readable, no bulk
      rewrite. 9b. `VaultRotationTest` rotates a populated vault and asserts
      every card ciphertext is byte-identical afterwards, that the vault reads
      correctly at every point of a partial rotation, and that a retired key
      version makes exactly the rows still naming it unreadable
- [ ] mTLS between all internal services
- [x] API keys hashed at rest, rotation demonstrated — 9b,
      [experiment 24](../experiments/24-key-rotation.md). Hashing was already
      true since V2 in phase 1; ticking the criterion on that alone would have
      been defensible and wrong, because **hashing at rest is not what limits
      exposure for an API key**. One key per merchant means replacing it is a
      simultaneous edit on two sides, so it is done rarely, so keys live for
      years.

      V16 gives a merchant several keys with three states — `ACTIVE`,
      `RETIRING` (the overlap), `REVOKED` — and drops `merchant.api_key_hash`
      in the same migration, because two places a credential is checked against
      is how a revoked key keeps working.

      Measured under continuous traffic, because a rotation script run against
      an idle system demonstrates replacement rather than rotation:

      ```
      requests during the whole rotation    53
      rejected with 401                      0
      ```

      Verified live on MySQL, not only H2: migration applied, both merchants
      backfilled, a real payment returned 201, a bogus key 401, `last_used_at`
      stamped only for the merchant that called.

      Two design points the experiment forced. **Expiry is enforced on the read
      path, not by a job** — a job that flips `RETIRING` to `REVOKED` is exactly
      the kind nobody notices has stopped, and its failure is invisible because
      everything keeps working with twice as many live credentials as anyone
      believes. The run asserts the row still reads `RETIRING` while the key is
      refused. And **`last_used_at` is throttled to one write per key per
      minute**, because the naive version adds ~279 writes/s (experiment 23's
      number) to maintain a timestamp whose only consumer is a human — which in
      turn means its staleness sets a floor on how long quiet must be observed
      before it can be trusted

### Traps

**Vault in dev mode in a demo you call production-shaped.** Say which mode you
are running and why.

**Storing the DEK next to the ciphertext unencrypted.** The DEK must be stored
*wrapped*. Easy to get right, easy to get subtly wrong — the subtly wrong version
is a refactor that stores the key it just generated instead of the wrapped one,
and every other test still passes. `theStoredKeyIsWrappedAndNotTheKeyItself`
tries to decrypt using the stored bytes directly and requires it to fail.

**`WHERE kek_version <> :current` misses every legacy row.** SQL three-valued
logic makes that predicate UNKNOWN for a NULL, so a rotation job written that way
reports success over rows it never looked at. Found in 9b; the fix is
`IS NULL OR <>`, and counting the ones that can never move.

**Assuming an append-only grant and key rotation can coexist.** They cannot at
table granularity. Column-level `GRANT UPDATE (…)` is the resolution, and it is a
stronger control than the table-level one it replaces.

**Comparing `byte[]` values inside a `Map` with `isEqualTo`.** Reference
equality — the assertion fails whether or not the bytes changed, and says nothing
about the thing under test. Cost one debugging round in 9b's headline test.

**Nonce reuse with AES-GCM.** Catastrophic — it leaks the XOR of plaintexts.
Generate per-encryption, never reuse a nonce under the same key.

---

## 9c. Access control, audit and retention

### Implementation

1. **PII access audit log** — a separate, immutable record of *who read what*.

   "We encrypted it" does not answer "who looked at it," and auditors ask the
   second question. Log every detokenization: who, when, which token, which
   payment, from which service.

2. **Role-based access on detokenization** — `psp-connector` can detokenize;
   nothing else can. Enforce at the vault's credential boundary (phase 1 gave
   `token_vault` its own credentials), not just in application code.

3. **Retention and right-to-erasure** (DPDP Act / GDPR).

   Tokenization makes this elegant: **delete the token→PAN mapping and every
   downstream copy becomes permanently meaningless** without touching a single
   other table. No chasing copies across MySQL, Mongo, Kafka topics, log
   archives and backups — which is the approach that never actually completes,
   because you cannot rewrite an immutable Kafka log or a backup tape.

   This is called crypto-shredding, and being able to explain why it beats
   copy-chasing is the strongest data-protection point in the project.

   #### 9c, as built (crypto-shredding; audit log still outstanding)

   **"Delete the token→PAN mapping" is not sufficient, and finding out why is
   the interesting part.** A `DELETE` removes the row from the live table and
   from nothing else. Restore last night's backup and the mapping is back — so
   an erasure satisfied by a delete is satisfied only until somebody restores.
   The phase's own trap list says as much: *"erasure that misses backups… only
   if the KEK/DEK material is also erased from backups."*

   So the thing destroyed has to be something that was never in the backup of
   the data. That is the **key**, held in a different system with a different
   backup lifecycle — which turns `KekStore` into an interface with one real
   requirement: *key material must not share a backup domain with the ciphertext
   it protects.*

   **Scope is the erasure boundary, and it cannot be chosen later.** Phase 9b
   gave every record its own DEK wrapped under one global KEK — which makes
   rotation cheap and erasure impossible, because there is no key whose
   destruction removes one merchant. Whatever unit the business must erase has
   to be the unit the key hierarchy names, *from the first record*: cards wrapped
   under a shared key cannot be separated afterwards without decrypting and
   re-encrypting them, which is the expensive migration all of 9b was arranged to
   avoid. The scope is therefore chosen at the edge, in the one place that knows
   whose card it is.

   **A derived key cannot be shredded**, which rules out the tempting
   alternative. `HKDF(master, merchantId)` needs no store and is stateless — and
   anybody holding the master can re-derive a "destroyed" key at any time, so
   erasure is unachievable by construction. A key that can be recomputed cannot
   be forgotten. Per-merchant keys must be independently generated and stored.

   **The scope is bound into the wrap AAD.** Without it, somebody with write
   access could relabel an erased merchant's row into a live merchant's scope
   and resurrect a card that had been erased — turning a completed erasure
   request back into a breach with one `UPDATE`.

   **Verified, including against a restore.**
   `erasingOneMerchantMakesTheirCardsUnrecoverableAndTouchesNoRow` erases one
   merchant and asserts their cards are unreadable, the other merchant is
   untouched, and **not one row was deleted or modified**.
   `restoringTheTableDoesNotUndoAnErasure` takes a copy of the table, erases,
   drops every row, restores the copy, and requires the card to still be
   meaningless. That is the assertion that separates crypto-shredding from
   `DELETE`: the row comes back, the key does not.

   **What is outstanding**: the PII access audit log, RBAC on detokenization
   beyond the existing credential grants, and Mongo TTL on raw payloads. And the
   `KekStore` shipped here holds keys **in memory** — correct for demonstrating
   erasure, useless for holding anything, since every merchant-scoped key dies
   on restart. Vault is the production implementation and is outstanding along
   with 9b's container.

4. **TTL enforcement** on raw payloads in Mongo (indexes from phase 8).

### Exit criteria

- [x] An erasure request renders one merchant's historical card data
      unrecoverable, **verified** — 9c. Per-merchant key scoping; erasure
      destroys the scope's key material and touches no row. Verified against a
      simulated backup restore, which is what distinguishes it from a `DELETE`
- [x] Audit log shows every detokenization event during a load run — 9c,
      [experiment 25](../experiments/25-vault-audit.md). Encryption answers
      "can somebody who steals this table read it"; it does not answer the
      question an auditor asks first. A service authorised to detokenize once
      per authorization, doing it a million times on a Tuesday night, was
      invisible to every control built so far.

      Asserted as **equality**, not "at least one" — a log holding some of the
      reads is not an audit trail:

      ```
      payments authorized                     40
      SUCCESS rows in the window              40
      rows with a correlation id              40
      rows with a trace id                    40
      ```

      Failures are recorded too, and they are the rows worth having: a run of
      `UNKNOWN_TOKEN` from one actor is somebody walking the token space, and
      nothing else in this system would show it. No PAN in any column —
      asserted — because a log of what was read is a second copy of the card
      and it is the copy nobody remembers to encrypt.

      **Fail-closed**: if the access cannot be recorded, the card is not read.
      That turned four `AuthorizationFlowTest` cases to 500 the moment it was
      enabled, because the test vault had no audit table — the design working,
      in the least convenient place, which is where a control like this is
      supposed to surface
- [x] Only `psp-connector` can detokenize; another service attempting it fails
      — 9c. Six controls, every one denied by MySQL with `ERROR 1142` rather
      than by a code review. Asserted on the error *number*: "connection
      refused" and "no such table" also produce an error and would mean the
      control was never exercised.

      | attempt | result |
      |---|---|
      | payment-orchestrator reads a card | denied |
      | the auditor reads a card | denied |
      | psp-connector reads its own audit trail | denied |
      | psp-connector erases an audit row | denied |
      | psp-connector rewrites an audit row | denied |
      | psp-connector plants a card | denied |

      The last three are the interesting ones. The audited service writes its
      own trail — there is no honest way around that without an out-of-process
      interceptor — so what is arranged instead is that the record is beyond
      its reach once written: `INSERT` and nothing else, **including no
      `SELECT`**, so a compromised connector cannot read back what has been
      recorded about it. Append-only is tamper-evident, not tamper-proof.

      `vault_auditor` has no grant on `token_vault` at all: the account used to
      investigate card access cannot itself access cards
- [x] TTL demonstrably expires raw payloads — 9c,
      [experiment 26](../experiments/26-mongo-retention.md), with the criterion
      restated rather than met as worded. **This system has no raw-payload
      collection** — the ledger stores structured projections, and nothing
      persists a raw Kafka message or webhook body — so inventing one in order
      to expire it would be building a component to delete it. The TTL goes on
      `settlement_line`, which is genuinely raw third-party input and is
      re-ingestible; the `journal` deliberately gets none, because a retention
      control on a financial record is a data-loss feature wearing a compliance
      badge.

      ```
      ok   the 30-day-old line was expired                      0
      ok   the fresh line survived                              1
      ok   a line with no ingestedAt is NOT expired             1
        lines with no ingestedAt: 7 of 14
      ```

      **The drill's first output was that no Mongo index existed at all.**
      `@Indexed` has been on these entities since phases 6e and 8 — including
      `@Indexed(unique = true)` on `JournalEntry.eventId`, written as the guard
      against at-least-once redelivery — and Spring Data has defaulted
      `auto-index-creation` to false since 3.0. 47,452 journal documents, one
      index, `_id_`. Zero duplicates existed, which was the ordering doing the
      work rather than the constraint: the consumer checks MySQL first.

      Two findings the drill forced. **Retention fails open**: a document whose
      TTL field is missing is skipped silently and forever, and 7 of 14 lines
      were in that state — so the policy would have been false for precisely the
      oldest data. And the backfill takes its timestamp from the **ObjectId**,
      not the clock, because `now()` would have granted every historical line a
      fresh full retention period.

      Reconciliation re-measured, correcting experiment 22's stated cause:
      **64ms without the indexes, 30–35ms with**

### Traps

**An audit log that can be edited by the thing being audited.** Append-only, and
ideally a separate store with separate credentials.

**Erasure that misses backups.** This is exactly why crypto-shredding is the
right design — but only if the KEK/DEK material is *also* erased from backups.
Be precise about what the guarantee is. Answered in 9c by putting the guarantee
in `KekStore`'s contract: an implementation storing KEKs in the same database as
the ciphertext provides no erasure at all, and the test that proves the design
works is the one that restores a backup.

**Deriving per-tenant keys from a master key.** Stateless, elegant, and
unshreddable — the master can always re-derive what you "destroyed". If the
erasure boundary is the tenant, the tenant's key has to be independently
generated and stored.

**Choosing the erasure boundary late.** Records already wrapped under a shared
key cannot be separated without decrypting and re-encrypting every one of them.
The scope has to be right from the first record, which means it is a design
decision rather than a configuration one.

**Not binding the scope into the wrap.** Otherwise an erased record can be
relabelled into a live scope and read again, and a completed erasure becomes a
breach with one `UPDATE`.

**Audit logging the PAN itself.** The audit log records *that* a detokenization
happened, never its result.

---

## Interview payload

The PCI scope-reduction story end to end, and it is four answers most personal
projects cannot give:

| Question | Answer |
|---|---|
| Where does raw PAN exist? | `payments-edge` on arrival, `token_vault` at rest, `psp-connector` at call time |
| How is it protected? | Envelope encryption, per-record DEK, Vault-held KEK, rotatable without bulk rewrite |
| Who can reverse it? | One service, enforced at the credential boundary, every access audited |
| How does erasure work? | Drop the mapping; every downstream copy becomes meaningless |

**Be ready for:** *"You said raw PAN only exists at the edge — but the connector
detokenizes, so isn't it in scope too?"* Yes. Three components, one audited
reversal path. Claiming one component invites exactly this question, and having
pre-empted it is worth more than the smaller-sounding claim.
