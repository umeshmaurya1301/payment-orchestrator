# 28 — Server streaming, and a state I had already gotten wrong once

**Phase 9a item 4.** `payments.proto` (`Watch`), `PaymentsGrpcService.watch`,
`RestOrchestratorClient.watch`, `PaymentsController.events`,
`tools/loadtest/watch-vs-poll.sh`

---

## Hypothesis

A merchant waiting for a payment to leave `AUTHORIZING` — or to come back from
`UNKNOWN` — polls `GET /v1/payments/{id}` in a loop. Every poll costs an API-key
lookup, a rate-limit token, a deadline scope, an HTTP round trip and a database
read, and all but the last one returns exactly what the previous one did. Server
streaming's whole claim is that a stream can pay once and deliver only on
change.

**The claim needs a precise boundary or it overstates itself.** This state
machine has no change feed — nothing publishes a transition in-process — so the
orchestrator side of a stream still has to *watch by re-reading*, on an
interval, the same as the REST arm does. What streaming removes is not polling;
it is the *N* in "N clients each polling separately". One loop in the
orchestrator replaces N loops across N merchants. That is a real reduction and
it is not the same as none, and the prediction was that this experiment would
show exactly that shape: identical merchant-visible latency, because both arms
check on the same interval, and a large drop in requests that cross the internal
hop.

The merchant-facing surface is one endpoint on the edge —
`GET /v1/payments/{id}/events`, Server-Sent Events — backed by whichever
transport is wired: a gRPC server stream, or a REST polling loop. The interval is
a parameter on both sides and held equal by the measuring script, for the reason
experiments 19 and 23 both learned the hard way: an unequal interval measures the
interval, not the transport.

## Setup

```
tools/loadtest/watch-vs-poll.sh
```

One payment is created, a capture is fired from a separate process 4 seconds in,
and the SSE stream is read to completion. The number that matters is
`http.server.requests` on the orchestrator's internal `GET /internal/v1/payments/{id}`
endpoint — the REST arm's poll target — counted before and after the watch.

## Actual result

```
                                     REST (poll) gRPC (stream)
   orchestrator GETs                          40            0
   SSE frames delivered                        2            2
   stream ended after                     10283ms       10170ms

   ok   both arms delivered the same states                AUTHORIZED CAPTURED
   ok   the stream cost fewer internal requests            40 -> 0
```

Same states, same order, ending within 113ms of each other — and **40 internal
HTTP requests against 0.**

The zero needs one honest qualification: it measures requests to a specific HTTP
endpoint, and the gRPC arm's `Watch` RPC calls `PaymentService.find` as a direct
in-process method inside the orchestrator, never through that endpoint. It is not
that the orchestrator does zero reads on the gRPC arm — it still re-reads the
payment roughly every 250ms, the same as the REST arm. What disappears is
everything *around* the read on 40 of those checks: the second network hop, HTTP
framing, Jackson (de)serialization and a Tomcat thread, all spent 39 times to
report "nothing changed."

## What surprised me

**Both arms delivered the same two states and still disagreed by 6 seconds on
when the stream ended, and that disagreement was the finding.** The first
comparison run showed REST completing at 4.2s and gRPC running the full 10s
budget — same states delivered, wildly different endings. My first reaction was
to suspect the gRPC arm of not detecting completion. It was the opposite: the
gRPC arm was *correct*, and the REST arm was ending early on a state that does
not end anything.

`RestOrchestratorClient` carries its own copy of "which states are final",
because the edge deliberately does not share `PaymentState` with the
orchestrator — sharing the enum would put two services on one release cycle for
the sake of a list of strings. That copy listed `CAPTURED` as terminal. It is
not:

```java
table.put(CAPTURED, Set.of(SETTLED, REVERSED));
```

`CAPTURED -> REVERSED` is phase 6k's compensation saga. With `CAPTURED` marked
terminal, **the REST-backed watch would stop the instant a payment was
captured and could never deliver a reversal that followed it** — silently. No
error, no timeout, just a stream that closed one transition too early and a
merchant who stopped watching believing the story was over.

**I wrote the failure mode down before I wrote the bug.** The javadoc on that
`TERMINAL` set, written before this experiment ran, predicted: *"the failure it
produces is a watch that never completes for a state somebody added."* That is
the plausible-sounding failure — forget to add a new state, hang forever. The
failure that actually happened was the mirror image and arguably worse: a state
*wrongly marked final*, ending a stream too early and dropping data with no
symptom at all. A hung stream times out and gets noticed. A stream that closes
early looks exactly like success.

It was found only because two transports disagreed on the same input. Neither
transport's tests alone would have caught it — the REST arm's early stop is
internally consistent and every one of its own assertions would have passed
before this experiment existed to compare it against something else. This is the
same category of finding as experiment 27's `ApiException` gap: **a defect that
only becomes visible when two implementations of one contract are run side by
side**, and this project keeps finding one of those per gRPC hop.

Fixed by restricting `TERMINAL` to states with no outgoing edge at all in
`PaymentTransitions.ALLOWED` — `FAILED`, `REVERSED`, `SETTLED`, `UNRESOLVED` —
verified against that table rather than against what a payment "usually" does
after capture. Pinned by `aCapturedPaymentThatIsLaterReversedIsStillDelivered`,
and mutation-checked: reintroducing `CAPTURED` fails exactly that one test.

**The false-terminal state also inflated the very number this experiment set out
to measure.** Before the fix, the REST arm's watch stopped at ~4s, so the request
count it racked up before stopping (18) was an undercount of what a full-budget
watch actually costs. After the fix, both arms ran the same 10-second budget and
the honest comparison is 40 requests against 0 — a bigger gap, and the correct
one, only visible once the bug was gone.

**The SSE emitter's own timeout has to exceed the watch budget, not equal it.**
`SseEmitter(budget.toMillis())` races Spring's own async timeout against the
watch completing normally — equal values mean the framework can abort the
connection at the exact instant the watch would have ended cleanly, turning every
single stream into a logged `AsyncRequestTimeoutException` instead of a quiet
completion. A two-second margin removes the race without meaningfully changing
the bound a merchant experiences.

## Standing questions

- **The interval is fixed per watch, not adaptive.** A payment sitting in
  `AUTHORIZING` for its whole budget is checked as often on second 1 as on
  second 29. A backoff would reduce the internal read count further and is not
  built.
- **Cancellation is not measured.** Both clients handle a merchant closing the
  connection — `CANCELLED` ends the gRPC stream quietly, and an `IOException` on
  `emitter.send` ends the REST loop — but nothing in this experiment forces a
  mid-stream disconnect and checks that the server side actually stops polling
  rather than continuing until its budget expires regardless.
- **`WatchPaymentRequest.poll_interval_ms` is caller-supplied with a wide open
  range.** `PaymentsController` clamps it to 50–5000ms before it reaches either
  client; the gRPC service itself does not, so a caller talking to the
  orchestrator directly rather than through the edge could request an interval
  the edge would have rejected.
- **This measures one merchant, one payment.** The claim is about the internal
  hop under N concurrent watches, not one. A load-test version — many merchants
  watching many payments simultaneously — would show the request reduction
  multiply and is not what this experiment ran.
