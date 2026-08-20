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
- [ ] Deadlines propagate over metadata; a short deadline fails fast at the connector
- [ ] Server streaming works for status
- [ ] gRPC → HTTP status mapping documented and tested
- [ ] **Benchmark written up** — throughput, P99, payload size, CPU

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
- [ ] API keys hashed at rest, rotation demonstrated

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

4. **TTL enforcement** on raw payloads in Mongo (indexes from phase 8).

### Exit criteria

- [ ] An erasure request renders one merchant's historical card data
      unrecoverable, **verified**
- [ ] Audit log shows every detokenization event during a load run
- [ ] Only `psp-connector` can detokenize; another service attempting it fails
- [ ] TTL demonstrably expires raw payloads

### Traps

**An audit log that can be edited by the thing being audited.** Append-only, and
ideally a separate store with separate credentials.

**Erasure that misses backups.** This is exactly why crypto-shredding is the
right design — but only if the KEK/DEK material is *also* erased from backups.
Be precise about what the guarantee is.

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
