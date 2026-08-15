#!/usr/bin/env bash
#
# Phase 1 exit criterion: a scan of every table outside token_vault, and of all
# captured container log output, finds zero Luhn-valid card numbers.
#
#   ./gradlew build && docker compose up -d --build
#   k6 run tools/loadtest/smoke.js
#   tools/panscan/pan-scan.sh
#
# Run it AFTER traffic has flowed. Scanning an idle stack proves nothing: the
# whole question is whether a card number survives a real payment.
#
# Exits non-zero if anything is found, so it can gate a script or, from phase 4,
# a build.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${ROOT}/tools/panscan/out"
mkdir -p "${OUT}"

STARTER_JAR="$(ls "${ROOT}"/infra-core/logging-starter/build/libs/logging-starter-*.jar 2>/dev/null \
    | grep -v sources | head -1 || true)"

if [[ -z "${STARTER_JAR}" ]]; then
    echo "logging-starter jar not found - run ./gradlew build first" >&2
    exit 2
fi

# ---------------------------------------------------------------------------
# 1. Every table in the application database.
#
# `--databases payorch` and nothing else, deliberately. token_vault lives in the
# separate payorch_vault database, and it is the one place a card number is
# allowed to exist. Dumping it here would either produce a false positive or,
# worse, tempt someone to add an exclusion that quietly grows.
# ---------------------------------------------------------------------------
echo "dumping payorch (every table outside the vault)..."
docker compose exec -T mysql \
    mysqldump -uroot -proot --databases payorch \
    > "${OUT}/payorch.sql" 2>/dev/null

# ---------------------------------------------------------------------------
# 2. All captured container output, every service, from the beginning.
#
# --no-log-prefix so the service name is not prepended to every line; the
# scanner reports by file, and the prefix would only pad the excerpts.
# ---------------------------------------------------------------------------
echo "capturing container logs..."
docker compose logs --no-color --no-log-prefix --tail=all > "${OUT}/containers.log"

# ---------------------------------------------------------------------------
# 3. Scan both with the same Luhn check the runtime masking filter uses.
# ---------------------------------------------------------------------------
echo
java -cp "${STARTER_JAR}" "${ROOT}/tools/panscan/PanScan.java" \
    "${OUT}/payorch.sql" "${OUT}/containers.log"
