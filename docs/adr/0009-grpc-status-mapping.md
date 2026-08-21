# 0009 — Carrying "maybe charged" over a gRPC status

**Phase 9a.** Status: accepted, with a known unsafe edge.

---

## Context

This system distinguishes two failures that look identical from the outside and
mean opposite things about a cardholder's money:

| | The card was | The payment becomes | A merchant may |
|---|---|---|---|
| Refused before sending | **definitely not charged** | `FAILED` | retry immediately |
| Sent, no answer | **possibly charged** | `UNKNOWN` | not retry; a lookup decides |

Over HTTP that distinction has a natural home. The connector answers **503** when
one of its own gates refused the call and **502** when it reached a provider and
got nothing back, and those are different numbers that no intermediary rewrites.
Phase 1 spent real effort establishing it and phases 3 and 8 are built on it —
the circuit breaker exists to *prevent* `UNKNOWN`s, and the status poller exists
to *resolve* them.

Phase 9a puts the same hop on gRPC, and gRPC has no code that means "I tried and
do not know". `UNAVAILABLE` is documented as the correct code for both cases.
Collapsing them would turn every open breaker into an `UNKNOWN` payment and a
poll — manufacturing precisely the uncertainty the breaker was built to avoid.

So: how does the connector say "possibly charged" over gRPC?

## Options considered

### 1. `Status.UNKNOWN` for the maybe-charged case — rejected

It has the right name and it is the wrong mechanism. `UNKNOWN` is what gRPC
turns an *unhandled server exception* into by default. Adopting it as the signal
for "the provider may have acted" means every NullPointerException in
psp-connector manufactures a maybe-charge, a status poll, and a payment that
cannot be retried — and it does so silently, because the default path never
touches our code.

The word is already spent. `ConnectorGrpcService` deliberately maps unclassified
exceptions to `INTERNAL` for the same reason.

### 2. A distinct existing code, e.g. `DATA_LOSS` or `ABORTED` — rejected

Tempting because it is one line. The problem is that gRPC status codes are a
*public* vocabulary: generic retry middleware, service meshes and client
libraries make decisions from them without asking us. `ABORTED` invites
automatic retry, which is the one thing that must not happen to a possibly-
charged payment. Borrowing a code with existing semantics makes other people's
tooling act on our private meaning.

### 3. `google.rpc.Status` error details — rejected for now, and it is the strongest option

The designed answer: attach a typed protobuf message to the status, carried in
trailing metadata as an `Any`. It is structured, versionable, and survives being
read by code that does not know about it.

Rejected only on cost, and the cost is real: `io.grpc.protobuf.StatusProto` on
both ends, a new message type in the proto, and the details path is easy to get
subtly wrong in a way that degrades to *no* details rather than to an error.
This is the option to take if the mapping ever grows past its current three
cases.

### 4. A custom trailing metadata key — rejected

Lighter than 3 and most of the same benefit. Rejected because it is 3 without the
schema: an untyped string in a header we invented, needing the same plumbing on
both ends, with no way for anything else to interpret it. If the plumbing is
being written, write it against the standard.

### 5. The status **description** string — chosen

`UNAVAILABLE` with the description `provider_unavailable` means possibly charged;
`UNAVAILABLE` with any other description means refused. One constant, shared as
`ConnectorGrpcService.OUTCOME_UNKNOWN`, read in one place in
`GrpcConnectorClient.translate`.

## Decision

The full mapping, tested in both directions:

| Cause at the connector | gRPC status | description | Client throws | Payment |
|---|---|---|---|---|
| Circuit breaker open | `UNAVAILABLE` | `circuit_open` | `ConnectorRejected` | `FAILED` |
| Bulkhead full | `UNAVAILABLE` | `bulkhead_full` | `ConnectorRejected` | `FAILED` |
| Egress rate limited | `RESOURCE_EXHAUSTED` | `provider_rate_limited` | `ConnectorRejected` | `FAILED` |
| Provider did not answer | `UNAVAILABLE` | **`provider_unavailable`** | `ConnectorUnavailable` | **`UNKNOWN`** |
| Client gave up waiting | `DEADLINE_EXCEEDED` | *(any)* | `ConnectorUnavailable` | **`UNKNOWN`** |
| A bug in the connector | `INTERNAL` | `internal_error` | `ConnectorUnavailable` | **`UNKNOWN`** |
| Anything else | *(any)* | *(any)* | `ConnectorUnavailable` | **`UNKNOWN`** |

`RESOURCE_EXHAUSTED` earns its own row for the same reason `Retry-After` exists
in the REST arm: it means the budget is ours, so retrying sooner cannot help.
Generic retry layers treat it differently from `UNAVAILABLE`, and here that
happens to be correct.

**The last row is the decision that matters most.** There are exactly two
exception types and therefore no "I don't know" branch, so every unrecognised
status has to land somewhere, and it lands on `UNKNOWN`. A payment wrongly marked
unknown costs one status poll; a payment wrongly marked failed while the card was
charged costs a refund, a chargeback and a customer. The asymmetry is not close.

Tested by `ConnectorGrpcStatusMappingTest` (exception → status, in psp-connector)
and `GrpcStatusTranslationTest` (status → exception, in payment-orchestrator).
Both use in-process gRPC transport rather than mocked stubs, so a status has to
survive the real serializer to satisfy them. The pair is deliberately two tests
in two modules: each half is a property of one service, and the wire is what they
agree about.

## Consequences

**A string is a poor contract and this one is load-bearing.** `provider_unavailable`
is the entire difference between "retry freely" and "do not touch this until a
lookup answers". Nothing validates it — a typo on either side compiles, passes
every other test, and silently reclassifies every ambiguous payment.

**Descriptions are not guaranteed to survive intermediaries.** A proxy, a mesh
sidecar or a load balancer may replace a status description with its own. This is
the specific weakness option 3 does not have, and it is the reason to expect this
decision to be revisited when anything sits between these two services.

**A bare `UNAVAILABLE` maps to `FAILED`, and that is the unsafe direction.** With
no description — a refused connection, a proxy that rewrote it — the client reads
"refused". That is correct for connection-refused, which is the common case, and
it is the one place the safe-side rule above is knowingly not applied. If
anything ever swallows a real provider timeout into a bare `UNAVAILABLE`, a
possibly-charged payment gets marked `FAILED`. `GrpcStatusTranslationTest`
records this in `aBareUnavailableIsReadAsRefused` so that it stays a decision
rather than becoming a discovery.

**Two mappings now exist for one contract.** The REST client maps 502/503 and the
gRPC client maps statuses, and they must agree because `CONNECTOR_TRANSPORT`
switches between them at runtime. They are tested separately and nothing asserts
they are equivalent.

---

*Numbers and behaviour: [experiment 23](../experiments/23-rest-vs-grpc.md).
Related: [0006](0006-retry-budget.md) on why a retryable failure is expensive
here, and [0002](0002-semaphore-bulkhead.md) on the gate behind `bulkhead_full`.*
