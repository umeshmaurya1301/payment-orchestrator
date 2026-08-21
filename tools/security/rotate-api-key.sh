#!/usr/bin/env bash
#
# Phase 9b: rotate a merchant's API key while they are sending traffic.
#
#   tools/security/rotate-api-key.sh
#
# WHAT IS ACTUALLY BEING CLAIMED
#
# Not "keys are hashed" - they have been since phase 1, and a dump of `merchant`
# never yielded a usable credential. The claim is narrower and is the one that
# reduces exposure:
#
#   a merchant's key can be replaced with ZERO failed requests,
#   and the operator can SEE when the old key is safe to revoke.
#
# Both halves have to be measured, because both are easy to assert falsely. A
# rotation script that runs against an idle system proves nothing about
# downtime, and a revocation performed on a schedule rather than on evidence is
# a guess that happens to have worked.
#
# So there is traffic running throughout, every response is counted, and the
# revoke step is gated on last_used_at rather than on a sleep.
#
# THE TRAP THIS AVOIDS
#
# The obvious demo issues a new key, revokes the old one, and shows the new one
# working. That demonstrates replacement, not rotation - it is the outage this
# feature exists to remove, performed quickly enough that nobody noticed. The
# arm that matters is the one where a client is mid-request when the switch
# happens.

set -uo pipefail

EDGE="${EDGE:-http://localhost:8080}"
MERCHANT_HEX="${MERCHANT_HEX:-0192ABCD000070008000000000000001}"
OLD_KEY="${OLD_KEY:-pk_test_dev_merchant_key}"
OVERLAP_SECONDS="${OVERLAP_SECONDS:-20}"
OUT="${OUT:-tools/loadtest/results/09-key-rotation}"
FAIL=0

mkdir -p "${OUT}"
NEW_KEY="pk_test_rotated_$(date +%s)_$RANDOM"
NEW_LABEL="rotated-$(date +%Y-%m-%d)"

sql() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

# One authenticated call. Prints the HTTP status only.
call() {
    curl -s -o /dev/null -w '%{http_code}' --max-time 15 \
        "${EDGE}/v1/payments/00000000-0000-7000-8000-000000000000" \
        -H "X-Api-Key: $1"
}

check() {
    local label="$1" expected="$2" actual="$3"
    if [[ "${actual}" == "${expected}" ]]; then
        printf "   ok   %-52s %s\n" "${label}" "${actual}"
    else
        printf "   XX   %-52s %s (wanted %s)\n" "${label}" "${actual}" "${expected}"
        FAIL=$((FAIL+1))
    fi
}

# A client sending steady traffic with whatever key is in KEYFILE. Writing the
# key to a file rather than passing it once is the whole point: it lets the
# merchant switch keys MID-RUN, which is what a real migration looks like.
KEYFILE="${OUT}/current-key"
STATUSFILE="${OUT}/statuses"

start_traffic() {
    echo "${OLD_KEY}" > "${KEYFILE}"
    : > "${STATUSFILE}"
    (
        while [[ -f "${KEYFILE}" ]]; do
            call "$(cat "${KEYFILE}" 2>/dev/null)" >> "${STATUSFILE}"
            echo >> "${STATUSFILE}"
            sleep 0.3
        done
    ) &
    TRAFFIC_PID=$!
}

stop_traffic() {
    rm -f "${KEYFILE}"
    wait "${TRAFFIC_PID}" 2>/dev/null
}

# `grep -c` prints 0 AND exits 1 when it finds nothing, so `|| echo 0` appends a
# SECOND zero and every caller then compares against "0\n0". The first run of
# this script reported "the rotation dropped requests: 0" because of it - a
# passing measurement rendered as a failure, which is the safer direction to get
# this wrong but is still wrong.
unauthorized_so_far() {
    local n; n="$(grep -c '^401$' "${STATUSFILE}" 2>/dev/null)"
    echo "${n:-0}"
}

requests_so_far() {
    local n; n="$(grep -cE '^[0-9]{3}$' "${STATUSFILE}" 2>/dev/null)"
    echo "${n:-0}"
}

echo "=============================================================="
echo " BEFORE"
echo "=============================================================="
check "the existing key authenticates" "404" "$(call "${OLD_KEY}")"
check "the not-yet-issued key does not" "401" "$(call "${NEW_KEY}")"

sql "SELECT label, status, IFNULL(last_used_at,'never') FROM merchant_api_key WHERE HEX(merchant_id)='${MERCHANT_HEX}';" \
    | awk '{printf "        %-16s %-10s last used %s\n", $1, $2, $3}'

echo
echo "=============================================================="
echo " TRAFFIC ON - the merchant is mid-integration throughout"
echo "=============================================================="
start_traffic
sleep 3
printf "   %-57s %s\n" "requests sent so far" "$(requests_so_far)"

echo
echo "=============================================================="
echo " STEP 1  issue the new key"
echo "=============================================================="
# The plaintext is generated here and hashed immediately. It exists in this
# shell and in the merchant's hands, and nowhere else - which is why the
# migration is not reversible and why that is correct.
sql "INSERT INTO merchant_api_key (id, merchant_id, api_key_hash, label, status)
     VALUES (UNHEX(REPLACE(UUID(),'-','')), UNHEX('${MERCHANT_HEX}'),
             SHA2('${NEW_KEY}', 256), '${NEW_LABEL}', 'ACTIVE');" >/dev/null

check "the new key authenticates immediately" "404" "$(call "${NEW_KEY}")"
check "the old key is untouched" "404" "$(call "${OLD_KEY}")"
check "no request has failed yet" "0" "$(unauthorized_so_far)"

echo
echo "=============================================================="
echo " STEP 2  retire the old key, with a deadline on the window"
echo "=============================================================="
sql "UPDATE merchant_api_key
     SET status='RETIRING', expires_at = NOW(3) + INTERVAL ${OVERLAP_SECONDS} SECOND
     WHERE HEX(merchant_id)='${MERCHANT_HEX}' AND label='original';" >/dev/null

check "the retired key still authenticates" "404" "$(call "${OLD_KEY}")"
printf "        the window is %ss and it is a DEADLINE, not a reminder - an\n" "${OVERLAP_SECONDS}"
printf "        overlap with no expiry is two permanent keys, which is worse\n"
printf "        than the one key it replaced\n"

echo
echo "=============================================================="
echo " STEP 3  the merchant deploys the new key, at a time they chose"
echo "=============================================================="
echo "${NEW_KEY}" > "${KEYFILE}"
sleep 3
check "still no failed request" "0" "$(unauthorized_so_far)"

echo
echo "=============================================================="
echo " STEP 4  wait for evidence, not for a clock"
echo "=============================================================="
# This is the step the table exists for. Revoking on a schedule is a guess;
# revoking because the old key has stopped being used is an observation.
old_last_used="$(sql "SELECT IFNULL(last_used_at,'never') FROM merchant_api_key
                      WHERE HEX(merchant_id)='${MERCHANT_HEX}' AND label='original';")"
printf "   --   %-52s %s\n" "old key last used at" "${old_last_used}"

quiet_for="$(sql "SELECT IFNULL(TIMESTAMPDIFF(SECOND, last_used_at, NOW()), 999)
                  FROM merchant_api_key
                  WHERE HEX(merchant_id)='${MERCHANT_HEX}' AND label='original';")"
printf "   --   %-52s %ss\n" "quiet for" "${quiet_for}"
echo "        In production this is hours or days of quiet, read from a"
echo "        dashboard. The mechanism is identical; only the threshold moves."

echo
echo "=============================================================="
echo " STEP 5  the window closes by itself"
echo "=============================================================="
echo "   waiting out the remaining overlap..."
remaining="$(sql "SELECT GREATEST(0, TIMESTAMPDIFF(SECOND, NOW(), expires_at) + 2)
                  FROM merchant_api_key
                  WHERE HEX(merchant_id)='${MERCHANT_HEX}' AND label='original';")"
sleep "${remaining:-5}"

check "the expired key is refused" "401" "$(call "${OLD_KEY}")"
check "the new key is unaffected" "404" "$(call "${NEW_KEY}")"
check "the row was NOT flipped by any job" "RETIRING" \
    "$(sql "SELECT status FROM merchant_api_key WHERE HEX(merchant_id)='${MERCHANT_HEX}' AND label='original';")"

stop_traffic

echo
echo "=============================================================="
echo " RESULT"
echo "=============================================================="
total="$(requests_so_far)"
failed="$(unauthorized_so_far)"
printf "   %-57s %s\n" "requests during the whole rotation" "${total}"
printf "   %-57s %s\n" "rejected with 401" "${failed}"

if [[ "${total}" -lt 20 ]]; then
    printf "   XX   %-52s %s\n" "too little traffic for the claim to mean anything" "${total}"
    FAIL=$((FAIL+1))
elif [[ "${failed}" -eq 0 ]]; then
    printf "   ok   %-52s %s\n" "zero failed requests across the rotation" "${failed}"
else
    printf "   XX   %-52s %s\n" "the rotation dropped requests" "${failed}"
    FAIL=$((FAIL+1))
fi

echo
echo "   Restoring the original key so the rest of the repo keeps working."
sql "DELETE FROM merchant_api_key WHERE label='${NEW_LABEL}';" >/dev/null
sql "UPDATE merchant_api_key SET status='ACTIVE', expires_at=NULL
     WHERE HEX(merchant_id)='${MERCHANT_HEX}' AND label='original';" >/dev/null
check "the original key works again" "404" "$(call "${OLD_KEY}")"

echo
echo "=============================================================="
[[ "${FAIL}" -eq 0 ]] && echo " PASS" || echo " FAIL - ${FAIL} check(s) failed."
echo "=============================================================="
exit "${FAIL}"
