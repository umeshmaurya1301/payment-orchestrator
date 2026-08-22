#!/usr/bin/env bash
#
# The PAN-leak test. Phase 1's exit criterion, promoted in phase 4 to something
# that fails a build.
#
#   ./gradlew build && docker compose up -d --build
#   tools/panscan/pan-scan.sh            # scan whatever traffic has already run
#   tools/panscan/pan-scan.sh --load     # drive the k6 smoke suite first
#
# Run it AFTER traffic has flowed. Scanning an idle stack proves nothing: the
# whole question is whether a card number survives a real payment.
#
# Exits non-zero if anything is found, so it can gate a script or a Gradle task.
#
# ---------------------------------------------------------------------------
# The self-test is not optional, and it runs FIRST.
#
# A leak test is a control whose healthy state is silence, which makes it the
# easiest kind of test to break without noticing: a bad classpath, a scanner
# that cannot read its input, a regex that stopped matching, and it reports PASS
# forever. Every one of those failures looks exactly like success.
#
# So before scanning anything real, the scanner is pointed at a file containing
# a known-bad card number, VPA and mobile number, and is required to FAIL. Only
# a scanner that has just demonstrated it can go red is trusted to report green.
# ---------------------------------------------------------------------------

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${ROOT}/tools/panscan/out"
mkdir -p "${OUT}"

WITH_LOAD=0
[[ "${1:-}" == "--load" ]] && WITH_LOAD=1

STARTER_JAR="${HOME}/.m2/repository/org/infra/infra-logging/1.0.0/infra-logging-1.0.0.jar"

if [[ ! -f "${STARTER_JAR}" ]]; then
    echo "infra-logging jar not found at ${STARTER_JAR} - publish it from the" >&2
    echo "Infra-Core project first: ./gradlew publishToMavenLocal" >&2
    exit 2
fi

scan() {
    java -cp "${STARTER_JAR}" "${ROOT}/tools/panscan/PanScan.java" "$@"
}

# ---------------------------------------------------------------------------
# 0. Prove the detector can go red.
# ---------------------------------------------------------------------------
echo "=== self-test: the scanner must fail on known-bad input ==="
CANARY="${OUT}/canary.txt"
cat > "${CANARY}" <<'CANARY'
{"message":"authorizing card 4242424242424242 for merchant"}
{"message":"upi collect sent to ramesh.kumar@okhdfcbank"}
{"message":"otp delivered to 9876543210"}
CANARY

if scan "${CANARY}" > "${OUT}/self-test.log" 2>&1; then
    echo "SELF-TEST FAILED: the scanner reported clean on a file containing a PAN," >&2
    echo "a VPA and a mobile number. It is not detecting anything, so a green" >&2
    echo "result from it would mean nothing. Output:" >&2
    sed 's/^/  /' "${OUT}/self-test.log" >&2
    exit 2
fi

# It failed, which is what we wanted - but it has to have failed for the right
# reason. A scanner that exits 1 because the classpath is broken would pass this
# check while detecting nothing.
for expected in "card number" "VPA" "mobile number"; do
    if ! grep -q "${expected}" "${OUT}/self-test.log"; then
        echo "SELF-TEST FAILED: scanner did not report a '${expected}' finding." >&2
        sed 's/^/  /' "${OUT}/self-test.log" >&2
        exit 2
    fi
done
echo "self-test passed: PAN, VPA and mobile all detected"
rm -f "${CANARY}"
echo

# ---------------------------------------------------------------------------
# 1. Optionally drive real traffic.
#
# The scan is only as good as what has been through the system. `--load` makes
# the test self-contained for CI, where nothing has run yet.
# ---------------------------------------------------------------------------
if [[ "${WITH_LOAD}" == "1" ]]; then
    echo "=== driving the k6 smoke suite ==="
    MSYS_NO_PATHCONV=1 docker run --rm \
        --network payorch_payorch \
        -v "$(cd "${ROOT}" && pwd -W 2>/dev/null || pwd)/tools/loadtest:/scripts" \
        -e EDGE=http://payments-edge:8080 \
        -e SIMULATOR=http://mock-psp-simulator:8085 \
        grafana/k6:latest run /scripts/smoke.js > "${OUT}/k6.log" 2>&1
    echo "k6 finished (see ${OUT}/k6.log)"
    echo
fi

# ---------------------------------------------------------------------------
# 2. Every table in the application database.
#
# `--databases payorch` and nothing else, deliberately. token_vault lives in the
# separate payorch_vault database, and it is the one place a card number is
# allowed to exist. Dumping it here would either produce a false positive or,
# worse, tempt someone to add an exclusion that quietly grows.
# ---------------------------------------------------------------------------
echo "=== dumping payorch (every table outside the vault) ==="
if ! docker compose exec -T mysql \
        mysqldump -uroot -proot --databases payorch \
        > "${OUT}/payorch.sql" 2>/dev/null; then
    echo "mysqldump failed - refusing to report a clean scan" >&2
    exit 2
fi

# ---------------------------------------------------------------------------
# 3. All captured container output, every service, from the beginning.
#
# --no-log-prefix so the service name is not prepended to every line; the
# scanner reports by file, and the prefix would only pad the excerpts.
# ---------------------------------------------------------------------------
echo "=== capturing container logs ==="
if ! docker compose logs --no-color --no-log-prefix --tail=all > "${OUT}/containers.log" 2>/dev/null; then
    echo "docker compose logs failed - refusing to report a clean scan" >&2
    exit 2
fi

# ---------------------------------------------------------------------------
# 3b. Refuse to scan sampled logs.
#
# Phase 4f added trace-based log sampling, and at its intended 1% it would hand
# this scanner 1% of the lines. Every leak would then have a 99% chance of
# passing, and the build would go green for the same reason a coin comes up
# heads - silently, and differently every run.
#
# This is the same rule as the scanner's own self-test at the top of this file:
# a control that cannot be shown to be working is not a control. There the
# question was "can it go red at all"; here it is "did it see everything".
#
# LogSamplingInstaller logs one line per service at startup, unsampled, saying
# which way the switch is set. A missing line is treated as suspicious rather
# than as absent evidence, because an older image that predates the installer
# would also be silent - and that is exactly the case where a stale build could
# be sampling without saying so.
# ---------------------------------------------------------------------------
if grep -q "log sampling ENABLED" "${OUT}/containers.log"; then
    echo >&2
    echo "REFUSING TO SCAN: log sampling is ENABLED on at least one service." >&2
    grep -o "log sampling ENABLED at [0-9.]*%[^\"]*" "${OUT}/containers.log" | sort -u | sed 's/^/  /' >&2
    echo >&2
    echo "The scanner would inspect a fraction of the lines and report green on" >&2
    echo "a leak it never read. Re-run with payorch.logging.sampling.success-rate=1.0" >&2
    echo "(the default) before trusting this test." >&2
    exit 2
fi
if ! grep -q "log sampling DISABLED" "${OUT}/containers.log"; then
    echo >&2
    echo "REFUSING TO SCAN: no service asserted that log sampling is disabled." >&2
    echo "Expected one '${PAN_SAMPLING_MARKER:-log sampling DISABLED}' line per service at startup." >&2
    echo "Either the stack predates phase 4f, or its startup output was truncated -" >&2
    echo "and in both cases this scan cannot prove it read every line." >&2
    exit 2
fi
echo "  log sampling confirmed disabled - the capture is complete"

# ---------------------------------------------------------------------------
# 4. Scan both, with the same Luhn check and the same patterns the runtime
#    masking uses.
# ---------------------------------------------------------------------------
echo
echo "=== scanning ==="
scan "${OUT}/payorch.sql" "${OUT}/containers.log"
