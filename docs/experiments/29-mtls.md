# 29 — mTLS, and a raw TLS probe that lied

**Phase 9b.** `docker/certs/generate-certs.sh`, `GrpcServerConfiguration`,
`OrchestratorGrpcServerConfiguration`, `OrchestratorConfiguration`,
`EdgeConfiguration`, `GrpcMutualTlsTest`, `tools/security/mtls-demo.sh`

---

## Hypothesis

Both internal gRPC hops — `payments-edge → payment-orchestrator` and
`payment-orchestrator → psp-connector` — have run plaintext since phase 9a.
Nothing stopped any *other* container on the same Docker network from dialing
`psp-connector:9090` directly and speaking gRPC to it. Docker's bridge network
gives every container L3/L4 reachability to every other container's exposed
ports; the application never checked who was calling. That is the failure this
closes — **not** the network boundary, which these ports were never exposed
across anyway, but every other thing that gets a foothold inside it.

The prediction: a certificate the deployment's CA never signed, or no
certificate at all, should be unable to complete a call against either hop.

## Setup

A local CA and one identity per service, generated fresh into a named Docker
volume by `cert-init` — the same `mongo-init` pattern this project already
uses for one-shot setup that must run with plain `docker compose up` and
nothing else. `payment-orchestrator` carries one identity used both ways: a
server credential on the edge's hop, a client credential on the connector's,
because it is one service in both roles.

```
docker compose up cert-init
CONNECTOR_TRANSPORT=grpc ORCHESTRATOR_TRANSPORT=grpc \
  docker compose up -d --force-recreate payment-orchestrator psp-connector payments-edge
tools/security/mtls-demo.sh
./gradlew :services:payment-orchestrator:test --tests '*GrpcMutualTlsTest'
```

## Actual result

```
 THREE CALLERS, ONE PORT: payment-orchestrator:9091
   1. payments-edge's real certificate (the legitimate caller on this hop)
   ok   admitted, reached PaymentsGrpcService
   2. no certificate at all
   ok   refused before any gRPC call could complete
   3. a certificate this deployment's CA never signed
   ok   refused - well-formed is not the same as trusted

 THE SAME THREE CALLERS, THE OTHER HOP: psp-connector:9090
   1. payment-orchestrator's real certificate (the legitimate caller)
   ok   admitted
   2. no certificate at all
   ok   refused

 THE POSITIVE CONTROL: a real payment, both hops, mTLS on
   ok   a payment authorized over both mTLS hops
```

Four more assertions in `GrpcMutualTlsTest`, portable and Docker-free —
trusted caller accepted, no certificate refused, untrusted-CA certificate
refused, and the *client* refusing a server it does not recognise, so the
proof covers both directions of authentication.

## What surprised me

**The first probe said the opposite of what was true, and I nearly wrote that
down.** `openssl s_client` with no client certificate against
`psp-connector:9090` printed `CONNECTION ESTABLISHED`. My first read was that
`ClientAuth.REQUIRE` was not working. It was working — RFC 8446 §4.4.2 lets a
TLS 1.3 server accept an *empty* client certificate message at the raw
handshake layer and defer the actual decision upward. `openssl s_client`
completes a bare handshake and stops; it never opens an HTTP/2 stream, so it
never reaches the layer where grpc-java's transport actually inspects the
peer's certificate chain. **The handshake completing is not evidence the call
would have.** This is the third time this session a measurement tool has
looked authoritative while measuring the wrong layer — experiment 19's load
generator, experiment 23's rate limiter, and now a security probe that would
have reported a vulnerability that did not exist.

The fix was to stop asking "did the handshake complete" and start asking "did
the RPC." `grpcurl` against the live stack, and `GrpcMutualTlsTest` in the
JVM, both make an actual unary call and check whether it returns a business
answer or fails — and both agree: no certificate, refused; wrong CA, refused;
legitimate caller, a real `NotFound` from `PaymentsGrpcService`.

**Netty's own test-certificate utility does not work on this project's JDK.**
`SelfSignedCertificate` — the standard, zero-dependency way to generate test
certs in a grpc-java test suite — throws
`UnsupportedOperationException: OpenJdkSelfSignedCertGenerator not supported
on the used JDK version` on JDK 25, because its fallback path reflects into
`sun.security.x509` internals that JDK 25 has closed off, and its other path
needs BouncyCastle, which is not a dependency here. There is no public JDK API
to construct and self-sign an `X509Certificate`. `GrpcMutualTlsTest` shells
out to `keytool` instead — the one certificate tool guaranteed present
anywhere this project's tests run, because it ships inside the same JDK the
build already requires, run once per test into a `@TempDir`, no new
dependency added for a single test class.

**`grpc-netty-shaded` shades more than its name suggests, at the version this
project resolved to.** Documentation and most examples show
`io.grpc.netty.GrpcSslContexts` and `NettyServerBuilder` as *unshaded* public
API even inside the shaded artifact. Unzipping the actual resolved jar
(`1.80.0` — `libs.versions.toml` pins `1.68.1`, resolved upward by another
dependency) showed otherwise: `GrpcSslContexts`, `NettyServerBuilder` and
`NettyChannelBuilder` are all under
`io.grpc.netty.shaded.io.grpc.netty.*` in this build, not the unshaded path.
Trusting memory here would have produced imports that compile against
nothing and fail in the same spot every time; checking the jar directly took
two minutes and settled it.

**Windows/Docker Desktop host bind mounts are unreliable for freshly created
directories, and named volumes are not.** A rogue certificate generated into
a brand-new `/tmp/...` directory on the host was invisible inside a container
bind-mounting that same path seconds later — `docker cp` reported success,
`ls` on the host showed the files, and the container saw an empty directory.
The fix was to skip the host filesystem entirely: generate the rogue cert
directly into a Docker named volume and mount *that*. The real deployment's
own certs, generated by `cert-init` straight into `certs-data`, were never
exposed to this problem — which is the accidental reason the demo script's
main path was solid before its own diagnostic path was.

## Standing questions

- **CA-level trust, not per-service authorization.** Any certificate signed by
  this deployment's CA is accepted by any server that trusts that CA — a
  compromised `payments-edge` presenting its real certificate could call
  `psp-connector` directly, which today's grants do not distinguish from the
  orchestrator calling it. mTLS here proves *deployment membership*, not
  *which* service is calling. A SPIFFE-style identity check against the SAN
  would close that gap and is not built.
- **The REST arms are untouched, and REST is still the default.** `mTLS` gates
  the gRPC servers only; `ORCHESTRATOR_TRANSPORT=rest` and
  `CONNECTOR_TRANSPORT=rest` — the defaults — run exactly as before, in
  plaintext HTTP. This criterion is complete for the transport phase 9
  actually built; it does not encrypt the hops most deployments of this stack
  are currently using.
- **No certificate rotation.** `generate-certs.sh` is idempotent — it reuses
  what exists rather than reissuing — which means certificates live for the
  volume's entire lifetime. There is no expiry monitoring and no rotation
  procedure, unlike phase 9b's KEK rotation or the API key rotation in
  experiment 24.
- **`vault_reader`/`vault_writer`'s database credentials remain separate from
  and unrelated to this mTLS layer.** A service that authenticates
  successfully over mTLS still reaches MySQL with whatever plaintext
  credentials phase 1 gave it. The two trust boundaries do not compose into
  one story yet.
