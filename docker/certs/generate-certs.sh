#!/bin/sh
# Phase 9b. A local CA and one certificate per internal service, so mTLS is
# real rather than a config flag with nothing behind it.
#
# WHY THIS RUNS AS A COMPOSE SERVICE RATHER THAN A COMMITTED FILE
#
# A committed private key is a private key checked into git forever, readable
# by anyone who ever clones the repo, rotatable only by rewriting history. This
# project's own KEK precedent - phase 9b's KekStore, phase 9c's crypto-shredding
# - is built entirely around key material having its own lifecycle, separate
# from the data it protects. A cert baked into the image fails that standard
# before mTLS has protected anything.
#
# So certs are generated fresh into a named volume the first time the stack
# comes up, the same pattern mongo-init uses to initialise a replica set:
# idempotent, runs with `docker compose up` and nothing else, and a
# `docker compose down -v` produces a fresh CA on the next run exactly the way
# it produces a fresh database.
#
# WHAT "ONE CERT PER SERVICE" BUYS OVER ONE SHARED CERT
#
# A single wildcard cert shared by all three services would satisfy the TLS
# handshake and prove nothing about WHICH service is on the other end - any
# holder of the shared key could impersonate any of the three. A distinct
# identity per service, each with its own SAN naming exactly the one Docker
# service name it answers to, is what makes "psp-connector accepted this
# connection" mean "the caller presented psp-connector's own key" rather than
# "the caller presented a key our CA happened to sign".

set -eu

OUT=/certs
DAYS=825

mkdir -p "$OUT"

if [ -f "$OUT/ca.crt" ]; then
    echo "certs already present in $OUT - reusing them (docker compose down -v to regenerate)"
    exit 0
fi

# The CA. Self-signed, local, and its only job is to say "these three
# identities belong to this deployment" - it is never presented to anything,
# only used to sign and to verify.
openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "$OUT/ca.key" -out "$OUT/ca.crt" \
    -days "$DAYS" -subj "/CN=payorch-local-ca"

issue() {
    name="$1"
    openssl req -newkey rsa:2048 -nodes \
        -keyout "$OUT/$name.key" -out "$OUT/$name.csr" \
        -subj "/CN=$name"

    # subjectAltName, not CN alone. Modern TLS hostname verification (Java's
    # X509ExtendedTrustManager included) checks SAN and increasingly ignores
    # CN entirely - a cert with no SAN passes issuance and then fails every
    # real handshake, which is a worse failure than issuing it wrong, because
    # it looks correct until the moment it is used.
    #
    # DNS name is the Docker Compose SERVICE name, because that is the
    # hostname a gRPC client actually dials - "psp-connector:9090", not the
    # container name Docker also happens to assign.
    printf 'subjectAltName = DNS:%s\n' "$name" > "$OUT/$name.ext"

    openssl x509 -req -in "$OUT/$name.csr" \
        -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" -CAcreateserial \
        -out "$OUT/$name.crt" -days "$DAYS" -extfile "$OUT/$name.ext"

    rm -f "$OUT/$name.csr" "$OUT/$name.ext"
}

# The three services either side of the two internal gRPC hops:
#   payments-edge       -> payment-orchestrator   (client only)
#   payment-orchestrator -> psp-connector          (server for the first hop,
#                                                    client for the second)
#   psp-connector                                  (server only)
issue payment-orchestrator
issue psp-connector
issue payments-edge

# 644, not the openssl default of 600: these containers run as the unprivileged
# `payorch` user (see docker/Dockerfile), a different uid from whatever this
# init container runs as, and the volume is mounted read-only into all three -
# "other read" is what lets that user open its own key.
chmod 644 "$OUT"/*.crt "$OUT"/*.key

echo "generated: ca.crt + 3 service identities in $OUT"
ls -la "$OUT"
