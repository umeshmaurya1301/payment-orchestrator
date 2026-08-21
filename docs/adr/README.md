# Architecture decision records

Nine decisions this project would be asked to defend, each with the options
that were genuinely considered and the consequences — including the ones that
are unwelcome.

**An ADR with no rejected option is a description, not a decision record**, and
one whose every option loses to the thing that was actually built reads as
rationalisation. Two of these conclude against the component they are about:
[0002](0002-semaphore-bulkhead.md) records a bulkhead the experiment did not
justify, and [0001](0001-outbox-over-2pc.md) records machinery that introduced
two worse bugs than the one it fixed.

Numbers are traceable to a page in [`../experiments/`](../experiments/).

| # | Decision | Phase | The cost it accepts |
|---|---|---|---|
| [0001](0001-outbox-over-2pc.md) | Transactional outbox over 2PC | 6a–6d | At-least-once delivery forever, and a relay whose own bugs blocked the payment path |
| [0002](0002-semaphore-bulkhead.md) | Semaphore over thread-pool bulkhead | 3d | Cannot interrupt a call — and the experiment showed it takes success against a *slow* provider from 100% to 6.8% |
| [0003](0003-uuidv7-binary16.md) | UUIDv7 as `BINARY(16)` | 1 | Ids leak creation time, and every ad-hoc query needs `HEX()` |
| [0004](0004-choreography-over-orchestration.md) | Choreography for the capture saga | 6k | Saga state is distributed; "why was this reversed" spans two services and a topic |
| [0005](0005-mysql-mongo-split.md) | MySQL balances, Mongo journal | 6e | Two stores, and two writes that cannot be one transaction |
| [0006](0006-retry-budget.md) | Retry budget over naive retry | 3b | 67% success instead of 94% — deliberately |
| [0007](0007-tokenization-boundary.md) | Tokenization boundary and PCI scope | 1, 9b, 9c | Three components in scope, not one. The CVV is unavailable to any real integration |
| [0008](0008-infra-core-included-build.md) | `infra-core` as an included build | 0 | No version compatibility story, because there is only ever one version |
| [0009](0009-grpc-status-mapping.md) | A status description carries "maybe charged" | 9a | An unvalidated string is the only thing separating a refusal from a possible double charge — and a bare `UNAVAILABLE` resolves the unsafe way |

## The four questions the PCI story answers

[ADR 0007](0007-tokenization-boundary.md) in short, because it is the section
most personal projects cannot write:

| Question | Answer |
|---|---|
| Where does raw PAN exist? | `payments-edge` on arrival, `token_vault` at rest, `psp-connector` at call time — **three components, not one** |
| How is it protected? | AES-256-GCM under a per-record DEK, wrapped by a KEK, rotatable without rewriting a single card ciphertext |
| Who can reverse it? | One service, enforced by database grants rather than application code |
| How does erasure work? | Destroy the merchant's key material; every copy — live, replicated, backed up — becomes permanently meaningless, and none of them is touched |

What is **not** answered yet: *who read what*. The PII access audit log is phase
9c's outstanding half, and "we encrypted it" is not a reply to the question
auditors actually ask.
