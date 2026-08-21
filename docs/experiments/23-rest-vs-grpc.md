# 23 — REST against gRPC, and two benchmarks that measured the wrong thing

**Phase 9.** `tools/loadtest/rest-vs-grpc.sh`, `PayloadSizeTest`, `GrpcConnectorClient`

---

## Hypothesis

Phase 9a asks for the connector hop over gRPC and a benchmark against REST on
*throughput, P99, payload size and CPU*. The phase's own trap list supplies the
prediction, and it is a deflating one:

> Assuming gRPC is always faster. For small payloads over a warm connection the
> gap is often smaller than expected. Report what you measure, including if it
> is unflattering — that is more credible, not less.

Experiment 19 sharpened that further. It measured this system to be bounded by a
bulkhead, an egress budget and a deadline slice long before anything about the
thread model mattered. A payment does **one** connector call and several database
round trips, so re-encoding that one call should move a small fraction of a small
fraction.

So the prediction was: payload clearly smaller, throughput and latency
indistinguishable.

**The prediction was half right, and the half that was wrong was wrong because
the first two runs measured neither transport.**

## Setup

Two arms, identical in everything but `CONNECTOR_TRANSPORT`:

```
tools/loadtest/rest-vs-grpc.sh          # 30 VUs, 45s per arm
./gradlew :services:payment-orchestrator:test --tests '*PayloadSizeTest'
```

Both arms pool: `RestClient` keeps connections alive, and the gRPC channel is a
singleton bean with keep-alive — the trap list's "or you are measuring TCP
handshakes". Both run the same `ConnectorGrpcService` / `ConnectorController`
pair, and those delegate to the *same* services, so the breaker, bulkhead, egress
limiter and deadline slice all sit below both doors and cannot differ between
arms.

Payload size is measured in a **JUnit test**, not the shell script. It is the one
number of the four that is deterministic — serialized length does not vary with
the machine, the load generator or what else is running — so it belongs where it
is re-checked on every build rather than recorded once by hand.

## Actual result

### Payload — the deterministic part

```
AuthorizeRequest    JSON 185 bytes   proto  97 bytes   48% smaller
AuthorizeResponse   JSON 101 bytes   proto  37 bytes   63% smaller
field names 58 bytes of an 88 byte saving
```

### Throughput, latency and CPU — two independent runs

| | REST | gRPC | gRPC vs REST |
|---|---|---|---|
| payments, run A | 12,580 (279/s) | 14,141 (314/s) | **+12%** |
| payments, run B | 12,657 (281/s) | 14,298 (318/s) | **+13%** |
| p95, run A | 156.70 ms | 129.77 ms | **−17%** |
| p95, run B | 153.52 ms | 127.69 ms | **−17%** |
| mean CPU, run A | 559.2% | 568.8% | +1.7% |
| mean CPU, run B | 583.1% | 585.3% | +0.4% |

Both runs agree to within a percent. gRPC moved **13% more payments at 17% lower
p95 for the same CPU** — a real difference, larger than predicted, and one this
benchmark could not see at all until it was fixed twice.

## What surprised me

**The benchmark passed twice while measuring a rate limiter.** The first version
printed a clean table:

```
                       REST         gRPC
payments created       2347         2349
p95                 25.01ms      18.19ms
throughput difference     0%
```

Zero percent. I read that as confirmation of the hypothesis — the transport does
not matter, exactly as experiment 19 predicted — and started writing it up. The
k6 log said otherwise:

```
http_reqs .......... 457414   10151/s
http_req_failed .... 99.49%
payments created ... 2347     (52/s)
```

Ten thousand requests a second, of which **fifty-two** got through. The other
99.5% were rejected in about a millisecond without ever reaching a connector, so
the p95 being compared was the p95 of *rejections* — and a rejection is
byte-identical on both arms. The transport was roughly 0.5% of what was measured.

Fifty-two per second is not an emergent property. It is `application.yml`:

```yaml
merchant-per-sec: ${RATELIMIT_MERCHANT_RPS:50}
```

**The two arms agreed to within 0.1% because a token bucket three services
upstream decided the number before either transport was reached.** Any two builds
compared this way agree — including a build compared against itself with a
deliberate 200 ms sleep inserted. The result was not weak evidence for the
hypothesis, it was no evidence at all, and from the table alone it was
indistinguishable from strong evidence.

Widening the psp gates and the edge limits took throughput from 52/s to ~300/s, a
6× change, and only then did a transport difference become visible.

**This is experiment 19's mistake wearing a different costume.** That one's first
version measured its own load generator — a closed-loop shell script reporting
exactly 1200 on both arms, which was the generator's ceiling showing through.
This one measured its own rate limiter. Both times the symptom was identical and
seductive: *the two arms agree suspiciously exactly*, which reads as a clean
negative result and is actually the signature of a shared bottleneck upstream of
whatever is under test. `virtual-vs-platform.sh` already had `widen_gates()` for
precisely this reason and I did not carry it across.

The lesson is not "widen the gates". It is that **a benchmark needs an assertion
that it measured what it claims to measure**, and that assertion has to be
separate from the comparison it is making. The script now fails if fewer than
half the requests reached a connector — the check that would have caught this on
the first run instead of the third.

**A 27% latency difference that did not exist.** Before the limiter was found, I
ran the broken benchmark twice: REST p95 25.01 ms against gRPC 18.19 ms on the
first run, then 19.01 ms against 19.11 ms on the second. A 27% gap and a 0.5% gap
from the same build, minutes apart. Had I run it once — which is what a passing
script invites — I would have published a 27% improvement that was pure
run-to-run variance in the rejection path. The script no longer prints a
noise verdict at all, because a single pair of runs cannot support one.

**Two thirds of protobuf's saving is field names.** 58 bytes of an 88 byte saving
on `AuthorizeRequest` — `"amountMinor"` is eleven bytes of key for two bytes of
value, sent every time. This bounds the claim: the advantage scales with the
*number of fields*, not the size of the data, so "protobuf is 48% smaller" is a
fact about this message rather than about the format. A message carrying one
large blob would show almost nothing.

**gRPC being genuinely faster was itself the surprise.** The hypothesis, the trap
list and experiment 19 all pointed at "no measurable difference", and 13%
throughput at 17% lower p95 is more than the payload arithmetic explains — 88
bytes saved on a hop that also does several database round trips should not move
p95 by 26 ms. The likely cause is HTTP/2 multiplexing rather than encoding: 30
concurrent payments share one connection instead of contending for a pool. That
is a hypothesis, not a measurement, and it is recorded here as one.

## Standing questions

- **CPU is measured with `docker stats`, which is a sampler.** The script
  averages point samples every three seconds across two containers. That is good
  enough to say "these two are within 2% of each other" and not good enough to
  say which is lower. The equal-CPU row should be read as *no difference visible
  at this resolution*, not as *no difference*.
- **P99 is not reported; p95 is.** k6's default summary carries p90 and p95, and
  a p99 drawn from 12,000 samples over 45 seconds is thin. The phase asks for P99
  and this delivers p95, which is a substitution rather than a detail.
- **The multiplexing hypothesis is untested.** It could be measured — run the
  REST arm with one pooled connection against many, or force the gRPC channel to
  a single stream — and it has not been.
- **The gates come back down afterwards; the rows do not.** Each run leaves
  ~27,000 payments behind. Every later experiment that counts rows starts from a
  higher baseline, which is why the scripts here all measure deltas rather than
  totals.
- **Only the authorize hop is on gRPC in anger.** The benchmark drives payment
  creation, so `capture`, `reverse` and `lookup` are exercised by tests but not
  by this load. A transport difference on those paths is assumed, not measured.
