# ADR 0007 — The tokenization boundary, and what it claims about PCI scope

**Status:** accepted (phase 1, hardened in 9b/9c) · **Evidence:** phases 1, 9b, 9c

## Context

A card number arrives at the edge and must reach a provider. Between those two
points it should exist in as few places as possible — and the claim about *how*
few has to be one that survives an interviewer reading the code.

## Options considered

**Encrypt the PAN and carry it through the system.** Every service can make a
provider call and every service is in audit scope. Rejected: the scope is the
whole system, which is the outcome the exercise exists to avoid.

**Tokenize at the edge, and have the connector call an external vault
provider.** The correct production answer, and it removes the vault from scope
entirely. Not modelled here because there is no external vault in a local stack,
and pretending otherwise would make the interesting part — the credential
boundary — disappear into somebody else's API.

**Tokenize at the edge; store the mapping in a schema with its own
credentials; detokenize in exactly one service.** Three components hold raw PAN:
`payments-edge` on arrival, `token_vault` at rest, `psp-connector` at call time.

## Decision

The third, with access enforced by **database grants rather than application
code**: `vault_writer` has `SELECT, INSERT`; `vault_reader` has `SELECT`; the
ordinary application user has no grant on the vault schema at all. A bug that
calls `tokenize` from the connector fails at the database, not at code review.

## Consequences

- **The claim is "three components", not "one".** Saying one is wrong and an
  interviewer will find the connector in about ninety seconds. Three components
  with one audited reversal path is both true and stronger, and pre-empting the
  question is worth more than the smaller-sounding claim.
- `TokenVault` holds both directions in **one class**, deliberately: "raw PAN
  exists in exactly three places" is only defensible if reversal happens on one
  code path that can be pointed at. Scattering decrypt-and-use across adapters is
  how that claim quietly stops being true.
- **The CVV goes nowhere at all** — not stored, not hashed, not forwarded. That
  is a real constraint with a real cost: a production integration needs the CVV
  at authorization time, and real vaults hold it under a short TTL. This one does
  not, because "never stored" is the property being demonstrated and a TTL is
  still storage.
- The boundary is enforced by **method signatures**, not by convention: only
  `authorize` takes a `DetokenizedCard`. Capture, reversal and lookup reference
  the provider's own handle and have no parameter a card could arrive through.
  Pinned by tests, because the way this erodes is a new operation quietly given
  a card field because the record next to it had one.
- 9b/9c made the mapping **erasable without deletion** — destroying a merchant's
  key material renders their cards meaningless in every copy including backups.
  That is what turns "we tokenize" into an answer to a right-to-erasure request.
- The audit log — *who read what* — is **not built yet**, and "we encrypted it"
  does not answer the question auditors actually ask.
