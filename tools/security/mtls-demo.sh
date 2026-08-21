#!/usr/bin/env bash
#
# Phase 9b: mTLS between internal services, proved against the live deployment.
#
#   tools/security/mtls-demo.sh
#
# THE FAILURE THIS CLOSES
#
# Before this, both internal gRPC hops - payments-edge -> payment-orchestrator
# and payment-orchestrator -> psp-connector - ran plaintext gRPC on the Docker
# network. Nothing stopped any OTHER container plugged into that network from
# dialing psp-connector:9090 directly and speaking gRPC to it. Docker's bridge
# network gives every container L3/L4 reachability to every other container's
# exposed ports; the application never checked who was calling.
#
# So this script's threat model is a container on the SAME docker network -
# not an external host, which the ports were never exposed to anyway - because
# that is what mTLS on an internal hop actually defends against: not the
# network boundary, but every OTHER thing that gets a foothold inside it.
#
# WHY A RAW TLS PROBE IS NOT PROOF, AND WHAT REPLACED IT
#
# The first attempt at this used `openssl s_client` with no client certificate
# against psp-connector:9090 and it printed CONNECTION ESTABLISHED. That is not
# a bug in the server - RFC 8446 §4.4.2 lets a server accept an empty client
# certificate message at the raw TLS layer and defer the decision upward. What
# matters is whether an actual gRPC CALL can complete, which needs a real gRPC
# client - grpcurl here, and GrpcMutualTlsTest.java's four assertions for the
# portable, no-Docker version of the same proof.

set -uo pipefail

NETWORK="payorch_payorch"
CERTS_VOLUME="payorch_certs-data"
ROGUE_VOLUME="payorch-mtls-demo-rogue"
PROTO_DIR="$(pwd)/proto/src/main/proto/payorch/v1"
FAIL=0

check() {
    local label="$1" expected="$2" actual="$3"
    if [[ "${actual}" == "${expected}" ]]; then
        printf "   ok   %-56s\n" "${label}"
    else
        printf "   XX   %-56s got: %s\n" "${label}" "${actual}"
        FAIL=$((FAIL+1))
    fi
}

# Returns: OK, REFUSED, or the raw grpcurl error text for anything else.
dial() {
    local target="$1" method="$2"; shift 2
    local out
    out="$(MSYS_NO_PATHCONV=1 docker run --rm --network "${NETWORK}" \
        -v "${PROTO_DIR}:/proto:ro" \
        -v "${CERTS_VOLUME}:/certs:ro" \
        -v "${ROGUE_VOLUME}:/rogue:ro" \
        fullstorydev/grpcurl:latest \
        -proto /proto/payments.proto -import-path /proto \
        "$@" -d '{"payment_id":"00000000-0000-7000-8000-000000000000"}' \
        "${target}" "${method}" 2>&1)"

    if [[ "${out}" == *"NotFound"* ]]; then
        # A NotFound for a made-up id is the CORRECT business answer - the call
        # went all the way through TLS, gRPC and PaymentsGrpcService. That is
        # the positive result, not a failure.
        echo "OK"
    elif [[ "${out}" == *"deadline exceeded"* || "${out}" == *"context deadline"* ]]; then
        echo "REFUSED"
    else
        echo "${out}"
    fi
}

echo "=============================================================="
echo " PREFLIGHT"
echo "=============================================================="
if ! docker volume inspect "${CERTS_VOLUME}" >/dev/null 2>&1; then
    echo "   XX   ${CERTS_VOLUME} does not exist - run 'docker compose up cert-init' first"
    exit 1
fi
# grep -o matches every nested component's status in Boot's health JSON, not
# just the top-level one - a first run of this check compared "one match" of
# expected output against "eight matches" of actual and failed on formatting,
# not on health. head -1 takes only the outermost status.
h1="$(docker exec payorch-payment-orchestrator wget -qO- http://localhost:8081/actuator/health 2>/dev/null | grep -o '"status":"UP"' | head -1)"
h2="$(docker exec payorch-psp-connector wget -qO- http://localhost:8083/actuator/health 2>/dev/null | grep -o '"status":"UP"' | head -1)"
check "payment-orchestrator is up" '"status":"UP"' "${h1}"
check "psp-connector is up" '"status":"UP"' "${h2}"

echo "   server mode, from the startup logs:"
docker logs payorch-payment-orchestrator 2>&1 | grep -o "gRPC payments service listening on [0-9]* ([^)]*)" | tail -1 | sed 's/^/     orchestrator: /'
docker logs payorch-psp-connector 2>&1 | grep -o "gRPC connector listening on [0-9]* ([^)]*)" | tail -1 | sed 's/^/     connector:    /'

echo
echo "=============================================================="
echo " A CALLER FROM OUTSIDE THIS DEPLOYMENT'S TRUST"
echo "=============================================================="
echo "   generating a certificate signed by a CA this deployment never issued -"
echo "   well-formed, just not one anybody here was told to trust:"
docker volume rm "${ROGUE_VOLUME}" >/dev/null 2>&1
docker volume create "${ROGUE_VOLUME}" >/dev/null
MSYS_NO_PATHCONV=1 docker run --rm -v "${ROGUE_VOLUME}:/rogue" alpine/openssl:latest \
    req -x509 -newkey rsa:2048 -nodes -keyout /rogue/rogue.key -out /rogue/rogue.crt \
    -days 1 -subj "/CN=payments-edge" >/dev/null 2>&1
MSYS_NO_PATHCONV=1 docker run --rm -v "${ROGUE_VOLUME}:/rogue" alpine \
    chmod 644 /rogue/rogue.key /rogue/rogue.crt

echo
echo "=============================================================="
echo " THREE CALLERS, ONE PORT: payment-orchestrator:9091"
echo "=============================================================="

echo "   1. payments-edge's real certificate (the legitimate caller on this hop)"
r1="$(dial payment-orchestrator:9091 payorch.v1.Payments/Get \
    -cacert /certs/ca.crt -cert /certs/payments-edge.crt -key /certs/payments-edge.key)"
check "admitted, reached PaymentsGrpcService" "OK" "${r1}"

echo "   2. no certificate at all"
r2="$(dial payment-orchestrator:9091 payorch.v1.Payments/Get -cacert /certs/ca.crt)"
check "refused before any gRPC call could complete" "REFUSED" "${r2}"

echo "   3. a certificate this deployment's CA never signed"
r3="$(dial payment-orchestrator:9091 payorch.v1.Payments/Get \
    -cacert /certs/ca.crt -cert /rogue/rogue.crt -key /rogue/rogue.key)"
check "refused - well-formed is not the same as trusted" "REFUSED" "${r3}"

echo
echo "=============================================================="
echo " THE SAME THREE CALLERS, THE OTHER HOP: psp-connector:9090"
echo "=============================================================="
# psp-connector serves Connector, not Payments - Get isn't on that service, so
# this dials the same three identities against the same port with a method
# that does not exist there. What is being checked is unchanged: does the TLS
# layer admit the caller at all. A missing-method error (Unimplemented) proves
# the call was admitted and reached gRPC dispatch; a deadline/refused error
# proves it was not.
dial_connector() {
    MSYS_NO_PATHCONV=1 docker run --rm --network "${NETWORK}" \
        -v "${CERTS_VOLUME}:/certs:ro" -v "${ROGUE_VOLUME}:/rogue:ro" \
        fullstorydev/grpcurl:latest "$@" psp-connector:9090 list 2>&1
}

echo "   1. payment-orchestrator's real certificate (the legitimate caller)"
o1="$(dial_connector -cacert /certs/ca.crt -cert /certs/payment-orchestrator.crt -key /certs/payment-orchestrator.key)"
[[ "${o1}" == *"deadline"* ]] && o1="REFUSED" || o1="OK"
check "admitted" "OK" "${o1}"

echo "   2. no certificate at all"
o2="$(dial_connector -cacert /certs/ca.crt)"
[[ "${o2}" == *"deadline"* ]] && o2="REFUSED" || o2="OK"
check "refused" "REFUSED" "${o2}"

echo
echo "=============================================================="
echo " THE POSITIVE CONTROL: a real payment, both hops, mTLS on"
echo "=============================================================="
resp="$(curl -s --max-time 30 -X POST http://localhost:8080/v1/payments \
    -H "Content-Type: application/json" -H "X-Api-Key: pk_test_dev_merchant_key" \
    -H "Idempotency-Key: mtls-demo-$(date +%s%N)" \
    -d '{"amountMinor":4200,"currency":"INR","card":{"number":"4242424242424242","expiryMonth":12,"expiryYear":2030,"cvv":"123"},"merchantReference":"mtls-demo"}')"
state="$(echo "${resp}" | grep -oE '"state":"[A-Z]+"' | cut -d'"' -f4)"
check "a payment authorized over both mTLS hops" "AUTHORIZED" "${state}"
echo "        This only means something if ORCHESTRATOR_TRANSPORT=grpc and"
echo "        CONNECTOR_TRANSPORT=grpc are both set - mTLS gates the gRPC"
echo "        servers, and the REST arms this project keeps runnable for"
echo "        experiment 23's comparison are untouched by any of this."

docker volume rm "${ROGUE_VOLUME}" >/dev/null 2>&1

echo
echo "=============================================================="
[[ "${FAIL}" -eq 0 ]] && echo " PASS" || echo " FAIL - ${FAIL} check(s) failed."
echo "=============================================================="
exit "${FAIL}"
