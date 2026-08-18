# 13 — Webhook signing, and what it does not buy

**Phase 6h.** `tools/loadtest/webhook-security.sh`

---

## Hypothesis

"Webhooks are HMAC signed with timestamp replay protection" is a sentence
everyone writes and almost nobody measures. It bundles three separate guarantees:

| | |
|---|---|
| **authenticity** | the request came from the holder of the secret |
| **integrity** | the body has not been altered |
| **freshness** | it was sent recently |

The prediction, written before the run:

1. **Unsigned**, a forged `payment.authorized` for a payment that never existed
   will be accepted, because the receiver has no way to tell our webhook from
   anyone else's, and a captured delivery can be replayed indefinitely.
2. **Signed**, forgery and tampering will be refused.
3. **Freshness is oversold.** A tolerance window *bounds* a replay; it does not
   prevent one. Inside the window, a captured delivery is a genuinely valid
   request — correct MAC, correct body, correct timestamp — and nothing in the
   signature scheme refuses it.

Prediction 3 is the reason the experiment has four arms instead of two. An
experiment that only ran "unsigned" and "signed" would produce a green run and
leave the reader believing something false.

## Setup

The receiver is `docker/webhook-sink/sink.py` — a merchant's endpoint, written in
Python **from the header format rather than from `WebhookSigner.java`**. That is
the load-bearing choice in this experiment. Two halves of one codebase sharing
one helper always agree, including when both are wrong; a signing scheme is only
real if an implementation written from the documentation, in another language,
accepts it.

The scheme:

```
X-Payorch-Signature: t=1786790407,v1=9f2c…
X-Payorch-Event-Id:  01a01556-fb4e-745c-acb6-0392e9bb9d94

v1 = HMAC-SHA256(secret, "<t>." + rawBody)
```

Four arms, each a full restart of both the sender and the receiver, because the
sink reads its configuration at process start and the ledger reads whether to
sign at context start. A run that restarted only one would have a sender and a
receiver in different arms, and the result would look like a signature bug.

| Arm | Sender | Receiver |
|---|---|---|
| A | unsigned | accepts everything |
| B | signed | verifying, 300 s tolerance |
| C | signed | verifying, **2 s** tolerance |
| D | signed | verifying, 300 s, **deduplicates on event id** |

```
tools/loadtest/webhook-security.sh
```

## Graph

The measurement is the acceptance table, arm by arm. Each row is one HTTP request
to the merchant's endpoint.

```
                                              A         B         C         D
                                          unsigned   signed   signed/2s  +dedupe
   genuine delivery from the ledger          200       200       200       200
   forged, unsigned                          200       400        -         -
   forged, signed with another secret         -        400        -         -
   tampered body, genuine signature           -        400        -         -
   captured MAC, rewritten timestamp          -        400        -         -
   unmodified replay, inside the window      200       200        -        400
   unmodified replay, 5s after a 2s window    -         -        400        -
```

## Actual result

### A. What an unsigned endpoint costs

```
   ok   genuine webhooks accepted                      1
   ok   a forged INR 500,000 authorization             200
   ok   a captured delivery replayed five times        5

   sink totals              7 accepted, 0 rejected
```

Seven accepted, zero rejected. One of those was real. The forged one claims
`amountMinor: 50000000` — INR 500,000 — for `paymentId`
`00000000-0000-0000-0000-00000000dead`, a payment that does not exist in any
database in this system, and the merchant's endpoint took it with a 200.

That is the whole argument for signing, and it is worth having as a number rather
than as a paragraph: **the receiver cannot tell.** Not "might not notice" —
cannot. There is nothing in the request to check.

### B. Signed, and what it refuses

```
   ok   our signature verified by an independent impl  1
   ok   forged, unsigned                               400   missing_signature
   ok   forged, signed with another secret             400   bad_signature
   ok   tampered body, genuine signature               400   bad_signature
   ok   captured MAC with a fresh timestamp            400   bad_signature
   ok   an unmodified replay, inside the window        200
```

The first line is the one that matters most and is easiest to overlook: a Python
verifier written from the header format accepted a signature produced by
`javax.crypto.Mac`. Everything else in this arm is our own code refusing our own
forgeries.

The fourth row is the reason the timestamp is *inside* the MAC. An attacker
replaying a stale capture will obviously rewrite `t` to now — that is the first
thing anyone would try. If `t` were only a header, the freshness check would pass
and a MAC computed over the body alone would still verify, and the freshness
check would be decoration. Signing `t.body` makes "when this was sent" a claim
the sender is committed to.

And the last row is the finding.

### C and D. What actually refuses a replay

An unmodified replay is refused by exactly two things, and **neither of them is
the signature**:

```
   ARM C   the same replay, 5s later                   400   stale_timestamp
   ARM D   the identical replay, now                   400   duplicate_event
```

Arm C is what timestamp tolerance really does: it makes a capture **perishable**.
With a 2-second window, a delivery captured on the wire is worthless five seconds
later. With the 300-second window of arm B — which is the industry-normal value,
and is normal because clocks drift and receivers are slow — the same capture is
valid for five minutes.

Arm D closes it, and closes it **on the merchant's side**. Nothing about our
signature changed; the receiver simply remembered the event id. That is worth
being straight with an integrator about: at-least-once delivery means a merchant
will legitimately receive the same webhook twice, so they need idempotency
anyway, and the same mechanism is what makes a replayed capture harmless. It is
not a second feature. It is the same feature, and it is on their side of the
integration.

### E. The remaining phase 6 exit criterion

> A single SigNoz trace spanning the sync path **and** the async webhook delivery

```
   traceparent   00-d935b0ed4ca4deb44dc582bc1e4fd94e-…-01
   the receiver saw traceparent: 00-d935b0ed4ca4deb44dc582bc1e4fd94e-…

   payments-edge          http post /v1/payments             Server    t+0ms
   payments-edge          evalsha                            Client    t+3ms
   payments-edge          evalsha                            Client    t+4ms
   payments-edge          http post                          Client    t+16ms
   payment-orchestrator   http post /internal/v1/payments    Server    t+18ms
   payment-orchestrator   http post                          Client    t+37ms
   psp-connector          http post /internal/v1/authorize   Server    t+38ms
   psp-connector          evalsha                            Client    t+40ms
   psp-connector          psp.authorize                      Internal  t+41ms
   psp-connector          http post                          Client    t+42ms
   mock-psp-simulator     http post /psp/v1/authorize        Server    t+44ms
   payment-orchestrator   outbox publish                     Producer  t+228ms
   ledger-notifier        payment.events process             Consumer  t+245ms
   ledger-notifier        http post                          Client    t+271ms
```

Fourteen spans, five services, one trace, from the merchant's request to the
webhook that tells them about it. The evidence is doubled deliberately: the
waterfall is what SigNoz holds, and `the receiver saw traceparent` is what the
merchant's own endpoint recorded off the wire. The second is the stronger of the
two, because it is exactly what would be missing if the `RestClient` had been
built without an observation registry — a case phase 4 already met once in
`MockPspAdapter`, and the reason that line has a fifteen-line comment on it.

### F. PII, at the widest audience in the system

```
   ok   digit runs of card length in the bodies        0
   ok   cvv/expiry/pan/token fields                    0
```

`WebhookEvent` is a separate record from `PaymentEventMessage` rather than a
serialization of it, for two reasons that point the same way. It is a published
contract, so an internal field rename must not break a dozen merchants' parsers.
And it goes further than any other copy of this data — a DLQ record at least
stays on our brokers — so `cardToken` is dropped. It is worthless without our
vault and it is also of no use whatsoever to the merchant, which is the test this
project has applied at every boundary: a field travels because somebody needs it,
not because it was already in scope.

## What surprised me

**Retries did not need to be built.** The plan for this unit had a delivery-attempt
table, a backoff scheduler and a second dead-letter path. None of it exists: a
delivery worth retrying throws, and phase 6f's ladder — 5 s, 1 m, 10 m, DLQ —
carries it. One retry mechanism for the service, one DLQ to replay from, one
place to look at 3am.

What makes that safe is phase 6e's idempotent `LedgerPosting`, and the surprise
is which way the dependency runs: the webhook retry works because the *ledger*
write is idempotent, not because the webhook is. It is the clearest example so
far of the project's own claim that these components compound rather than stack.

**Which meant the dispatch had to move out of the `applied` branch, and that
took a test to see.** The obvious placement is inside `if (applied)` — only
notify when something actually happened. It makes a single webhook failure
**permanent**: the ladder redelivers, `ledger.post` dedupes to `false`, the
dispatch is skipped, and the merchant is never told. `applied == false` means
"the ledger already had this event", not "the merchant already heard about it",
and nothing in the type system distinguishes those.

**A 4xx from a merchant is a deliberate drop, and there is no better answer.**
Retrying a request the receiver has *looked at and refused* — a bad signature, an
endpoint that moved — changes nothing over eleven minutes except their error
rate. So `WebhookDispatcher` counts it, logs it at WARN with the words "the
merchant was not told about this payment", and moves on. That is an event in the
ledger that never reached the merchant, and the only honest thing to do is give
it a name and a counter (`payorch.webhook.refused`) rather than let it look like
a success. 429 is the exception inside the exception: a 4xx that means "later",
so it is retried.

**Signing a webhook is mostly not about cryptography.** The HMAC is four lines.
The decisions that took the time were: serialize the body once so the bytes
signed are the bytes sent; put the timestamp inside the MAC; check freshness
before the MAC; compare with `MessageDigest.isEqual` rather than `equals`; refuse
to construct a signer with an empty secret; and write the verifier in a different
language so agreement means something. Every one of those is a way the scheme
fails while continuing to produce and accept signatures.

## Standing questions

- **Per-merchant secrets.** One shared secret today. Real integrations issue one
  per merchant, which turns the signer into a lookup and makes rotation a
  two-secret verification window on the receiver's side. That belongs with phase
  9c, where secrets stop being literals.
- **The 4xx drop needs an alert, not just a counter.** `payorch.webhook.refused`
  climbing means merchants are not being told about payments that happened, and
  it is invisible in every dashboard this project currently has. It joins
  consumer lag and DLQ `pending` on the list for the alerting unit.
- **Ordering.** Webhooks inherit Kafka's per-partition ordering, so a merchant
  sees one payment's events in order — but a redelivery from a retry tier arrives
  *after* later events for other payments, and a merchant sorting by arrival will
  be wrong. `createdAt` is in the body for exactly this reason and nothing
  enforces that they use it.
