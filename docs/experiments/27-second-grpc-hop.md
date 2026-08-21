# 27 — The second hop, and the same mistake made one floor up

**Phase 9a.** `payments.proto`, `PaymentsGrpcService`, `GrpcOrchestratorClient`

---

## Hypothesis

The criterion is *"all three internal hops on gRPC"*, and the phase text names
them: *"migrate orchestrator ↔ router ↔ connector"*.

**There is no router hop.** `psp-router` is a phase-0 skeleton — one class, a
health endpoint, no logic — and provider selection actually happens *in process*
inside the orchestrator (`HealthWeightedRouter`, phase 5), because a routing
decision that needs the orchestrator's breaker state, rolling success rate and
P99 is not improved by putting a network call in the middle of it. Adding a hop
to a service with nothing in it, so that a checklist item reads three, would make
the system worse in order to make the document truer.

So the internal hops that exist are two, and the second one is this:

```
payments-edge  ->  payment-orchestrator     payments.proto     (this experiment)
payment-orchestrator -> psp-connector       connector.proto    (experiment 23)
```

The prediction was that this would be mechanical. The first hop had already
established the shape — a proto, a service delegating to the same domain code, a
client behind an interface, a transport switch — and experiments 23 and the ADR
had already worked out the hard part, which is the status mapping.

**It was mechanical, and it still shipped a bug that ADR 0009 exists to prevent.**

## Setup

Both transports serve at once, selected by `ORCHESTRATOR_TRANSPORT`, exactly as
`CONNECTOR_TRANSPORT` selects one hop down. The gRPC server listens on 9091 —
not 9090, which psp-connector holds; a shared default works in containers and
collides the moment two servers run on one host, which is what debugging outside
Docker means.

Verified against the live stack rather than only in tests, because this session
has repeatedly found that code which has never run has a defect no test caught.

**The falsification test matters more than the happy path.** A client that
silently fell back to REST would produce a passing demo that proved nothing. So
the edge was restarted with `ORCHESTRATOR_TRANSPORT=grpc` *and*
`ORCHESTRATOR_BASE_URL=http://this-host-does-not-exist:9999`:

```
edge health=200  (REST base-url points at a nonexistent host)
{"id":"01a02515-6bc4-7292-9a8d-6f089ca54d5e","state":"AUTHORIZED", ...}
```

A payment succeeded with the REST target unreachable. That is proof rather than
assertion.

## Actual result

| | REST | gRPC |
|---|---|---|
| create | 201 `AUTHORIZED` | 201 `AUTHORIZED` |
| get | 200 | 200 |
| get, no such payment | 404 | 404 |
| capture | 200 `CAPTURED` | 200 `CAPTURED` |
| capture again | **409 Capture refused** | **502 Payment status unknown** ← |

The last row is the finding.

```
gRPC second capture -> 502
{"detail":"The payment could not be confirmed. It may or may not have been
 created. Retry with the same Idempotency-Key rather than a new one.",
 "errorCode":"orchestrator_unavailable"}
```

After the fix, both doors answer identically:

```
REST second capture -> 409
gRPC second capture -> 409
{"detail":"The payment could not be captured. It may already be captured,
 may not be authorized, or the provider declined.",
 "errorCode":"capture_refused"}
```

## What surprised me

**I wrote ADR 0009 about this exact mistake and then made it one hop up.** That
ADR's whole subject is that a refusal and an unknown outcome mean opposite things
and must not be collapsed. Three commits later, `PaymentService` raised
`ApiException(CONFLICT)`, `PaymentsGrpcService` had no branch for it, and the
generic `catch (Exception)` turned it into `INTERNAL` — which
`GrpcOrchestratorClient` maps to "no usable response" and the edge renders as
**502, "it may or may not have been created"**.

That is the worst available answer to a second capture. The payment is captured.
Nothing further happened. Repeating the request is pointless rather than
dangerous, and we know that with certainty — and the API said *we do not know*.

The mechanism is worth stating precisely, because it is not carelessness about
the mapping: **the mapping was written and it was written for the exceptions I
was thinking about.** `IllegalStateException` had a branch. `NotFound` had a
branch. `ApiException` — the one the service actually raises, from a starter
module, in code I did not open while writing the gRPC service — did not. A
default case that catches everything is exactly as safe as the author's memory of
what can be thrown.

**Nothing would have caught it.** 522 tests were green. The REST arm's behaviour
was correct and tested. The gRPC arm's happy path was correct and would have been
tested. What was untested was *the two doors agreeing*, which is not a property
of either door. It was found by capturing the same payment twice by hand.

**The fix is a translation, not a decision, and that is the point.**
`ApiException` already carries the HTTP status it means, so
`fromHttpStatus` maps 409 → `FAILED_PRECONDITION`, 404 → `NOT_FOUND`, 502/503/504
→ `UNAVAILABLE` — the last one preserving the genuine "we do not know" for the
case that really is one. Anything unrecognised becomes `INTERNAL` rather than
being guessed at, because silently mapping an unknown status onto "the caller can
fix this" turns a fault of ours into a merchant-visible rejection.

Mutation-checked: collapsing the branch back fails three tests, including the one
named `anAlreadyCapturedPaymentIsRefusedRatherThanUnknown`.

**`@Valid` does not exist on a gRPC method, and protobuf cannot replace it.** A
gRPC service method is not a Spring MVC handler, so nothing applies the Jakarta
constraints on the request record, and protobuf's type system cannot express
"amount is at least 1" or "currency is exactly three characters". Left alone, the
gRPC door would have accepted a zero-amount payment in a currency called `""`
that the REST door rejects with a 400. The constraints are duplicated explicitly
in `validate`, and `theTwoDoorsRejectTheSameRequests` holds them together.

**A third outcome appeared that the connector hop did not have.** ADR 0009 mapped
every connector status onto one question — was the request sent, or not — because
the orchestrator's state machine has exactly two branches. This hop has a third:
*the payment does not exist*, which is not a failure at all. It gets `NOT_FOUND`
and the client turns it back into `Optional.empty()`, exactly as the REST arm
turns a 404 into one. The ADR's two-way framing was correct for its hop and does
not generalise, which is worth knowing before the next service copies it.

## Standing questions

- **The two doors are still only checked by agreement in one direction.** The
  tests assert the gRPC service produces the right status for each exception. No
  test drives the same scenario through both clients and compares the merchant's
  response, which is the property that actually broke. Doing it properly needs
  both a running orchestrator and both clients, which is an integration test this
  project does not currently have a home for.
- **No benchmark for this hop.** Experiment 23 measured the connector hop at 13%
  more throughput and 17% lower p95 over gRPC. This hop is unmeasured, and the
  numbers will not transfer — the edge hop carries a larger payload and does
  tokenization work either side of it.
- **`state` is a string in the proto, deliberately.** An enum would make every
  new payment state a proto change deployed to two services in order. The cost is
  that nothing validates it, so a typo in the orchestrator becomes a state the
  edge silently does not recognise.
- **`created_at_ms` is an int64 rather than `google.protobuf.Timestamp`.** These
  two fields are carried, logged and echoed — never compared, never arithmetic —
  so the well-known type would buy type safety nothing uses. Recorded because
  "we used an int64 for a time" is otherwise indistinguishable from not knowing
  better.
- **`psp-router` still exists and still does nothing.** It builds, starts and
  answers a health check. Deleting it is the honest move and it is a decision
  about the phase plan rather than about the code.
