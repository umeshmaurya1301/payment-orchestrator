#!/usr/bin/env bash
#
# Phase 9c: who read a card, proved under load.
#
#   tools/security/vault-audit.sh
#
# THREE CLAIMS, AND NONE OF THEM IS "WE HAVE AN AUDIT LOG"
#
#   1. Only psp-connector can read a card, enforced at the credential boundary
#      rather than in application code.
#   2. EVERY detokenization during a load run appears in the log - not most,
#      not the ones on the happy path.
#   3. The service being audited can append to its trail and cannot read,
#      alter or erase it.
#
# Claim 2 is the one that needs load. An audit log verified with a single manual
# request proves the INSERT statement compiles. What it cannot show is whether
# the log keeps up when the path is busy, or whether some branch - a retry, a
# breaker trip, a token that does not exist - reaches the card without passing
# the recorder. Those branches are where a real audit gap lives, and they only
# execute under volume.
#
# WHAT THIS DELIBERATELY DOES NOT CLAIM
#
# The service writes its own audit entries. There is no honest way around that
# without an out-of-process interceptor, and this script does not pretend
# otherwise - it measures what CAN be arranged, which is that the record is
# beyond the writer's reach once written. Append-only is tamper-EVIDENT, not
# tamper-proof.

set -uo pipefail

EDGE="${EDGE:-http://localhost:8080}"
PAYMENTS="${PAYMENTS:-40}"
# 8083. The connector is NOT on 8082 - something else answers /actuator/health
# there, which is how a first version of this probe got a healthy 200 from the
# wrong service and a 404 from the right one.
CONNECTOR="${CONNECTOR:-http://localhost:8083}"
FAIL=0

audit() {
    docker exec payorch-mysql mysql -uvault_auditor -pvault_auditor_pw -N -B \
        -e "$1" 2>/dev/null | tr -d '\r'
}

as_user() {
    docker exec payorch-mysql mysql -u"$1" -p"$2" -e "$3" 2>&1 | tr -d '\r' | grep -v Warning
}

check() {
    local label="$1" expected="$2" actual="$3"
    if [[ "${actual}" == "${expected}" ]]; then
        printf "   ok   %-54s %s\n" "${label}" "${actual}"
    else
        printf "   XX   %-54s %s (wanted %s)\n" "${label}" "${actual}" "${expected}"
        FAIL=$((FAIL+1))
    fi
}

# A denial is a PASS here. Asserting on the error NUMBER rather than on any
# error, because "connection refused" and "no such table" would also produce a
# non-empty error and would mean the control is not being exercised at all.
denied() {
    local label="$1" user="$2" pw="$3" sql="$4"
    # The output is captured BEFORE it is matched, rather than piped into grep.
    # `set -o pipefail` is on, mysql exits non-zero when it is denied, and in a
    # pipeline that failure outranks grep's success - so `as_user ... | grep -q`
    # reports "no match" for every denial, which reads as ALLOWED. The first run
    # of this script called all six controls broken while all six were working.
    local out; out="$(as_user "${user}" "${pw}" "${sql}")"
    if [[ "${out}" == *"ERROR 1142"* ]]; then
        printf "   ok   %-54s %s\n" "${label}" "denied"
    else
        printf "   XX   %-54s %s\n" "${label}" "ALLOWED"
        FAIL=$((FAIL+1))
    fi
}

echo "=============================================================="
echo " 1. THE CREDENTIAL BOUNDARY"
echo "=============================================================="
echo "   Every one of these fails at MySQL, not at a code review."
denied "payment-orchestrator reading a card" \
       payorch payorch "SELECT token FROM payorch_vault.token_vault LIMIT 1;"
denied "the auditor reading a card" \
       vault_auditor vault_auditor_pw "SELECT token FROM payorch_vault.token_vault LIMIT 1;"
denied "psp-connector reading its own audit trail" \
       vault_reader vault_reader_pw "SELECT * FROM payorch_vault.vault_access_log LIMIT 1;"
denied "psp-connector erasing an audit row" \
       vault_reader vault_reader_pw "DELETE FROM payorch_vault.vault_access_log;"
denied "psp-connector rewriting an audit row" \
       vault_reader vault_reader_pw "UPDATE payorch_vault.vault_access_log SET actor='someone else';"
denied "psp-connector planting a card" \
       vault_reader vault_reader_pw \
       "INSERT INTO payorch_vault.token_vault (token) VALUES ('tok_planted');"

echo
echo "=============================================================="
echo " 2. COMPLETENESS UNDER LOAD"
echo "=============================================================="
before_rows="$(audit "SELECT COUNT(*) FROM payorch_vault.vault_access_log;")"
started="$(audit "SELECT NOW(3);")"
printf "   %-57s %s\n" "audit rows before" "${before_rows}"

echo "   sending ${PAYMENTS} payments..."
authorized=0
for i in $(seq 1 "${PAYMENTS}"); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 \
        -X POST "${EDGE}/v1/payments" \
        -H "Content-Type: application/json" \
        -H "X-Api-Key: pk_test_dev_merchant_key" \
        -H "Idempotency-Key: audit-$(date +%s%N)-${i}" \
        -d '{"amountMinor":4200,"currency":"INR","card":{"number":"4242424242424242","expiryMonth":12,"expiryYear":2030,"cvv":"123"},"merchantReference":"audit-load"}')"
    [[ "${code}" == "201" ]] && authorized=$((authorized+1))
done
sleep 3

success_rows="$(audit "SELECT COUNT(*) FROM payorch_vault.vault_access_log
                       WHERE at > '${started}' AND outcome = 'SUCCESS';")"

printf "   %-57s %s\n" "payments authorized" "${authorized}"
printf "   %-57s %s\n" "SUCCESS rows in the window" "${success_rows}"

# EQUALITY, not "at least one". A log with some of the reads in it is not an
# audit trail, and >= would pass a run where half the branches skipped the
# recorder. One authorization detokenizes exactly once.
check "every authorization produced exactly one audit row" "${authorized}" "${success_rows}"

echo
printf "   distinct references recorded: %s\n" \
    "$(audit "SELECT COUNT(DISTINCT reference) FROM payorch_vault.vault_access_log
              WHERE at > '${started}' AND outcome='SUCCESS';")"
printf "   rows with a correlation id:   %s\n" \
    "$(audit "SELECT COUNT(*) FROM payorch_vault.vault_access_log
              WHERE at > '${started}' AND correlation_id IS NOT NULL;")"
printf "   rows with a trace id:         %s\n" \
    "$(audit "SELECT COUNT(*) FROM payorch_vault.vault_access_log
              WHERE at > '${started}' AND trace_id IS NOT NULL;")"

echo
echo "=============================================================="
echo " 3. THE ROWS THAT MATTER ARE THE FAILURES"
echo "=============================================================="
echo "   A token that does not exist - what walking the token space looks like."
curl -s -o /dev/null --max-time 20 -X POST ${CONNECTOR:-http://localhost:8083}/internal/v1/authorize \
    -H "Content-Type: application/json" \
    -d '{"reference":"00000000-0000-7000-8000-00000000dead","pspId":"mockpsp","amountMinor":100,"currency":"INR","cardToken":"tok_does_not_exist_at_all","cardBin":"424242","cardLast4":"4242"}' 2>/dev/null
sleep 2

unknown="$(audit "SELECT COUNT(*) FROM payorch_vault.vault_access_log
                  WHERE at > '${started}' AND outcome = 'UNKNOWN_TOKEN';")"
check "the failed lookup was recorded too" "1" "${unknown}"
echo "        A successful read by an authorised service is the boring row."
echo "        A run of UNKNOWN_TOKEN from one actor is somebody probing, and"
echo "        this is the only place in the system where that is visible."

echo
echo "=============================================================="
echo " 4. WHAT AN INVESTIGATION ACTUALLY RUNS"
echo "=============================================================="
docker exec payorch-mysql mysql -uvault_auditor -pvault_auditor_pw \
    -e "SELECT actor, purpose, outcome, COUNT(*) AS reads,
               MIN(at) AS first_seen, MAX(at) AS last_seen
        FROM payorch_vault.vault_access_log
        WHERE at > '${started}'
        GROUP BY actor, purpose, outcome;" 2>/dev/null | tr -d '\r'

echo
echo "   And the row that proves the log is not a second copy of the card:"
docker exec payorch-mysql mysql -uvault_auditor -pvault_auditor_pw \
    -e "SELECT * FROM payorch_vault.vault_access_log ORDER BY id DESC LIMIT 1\G" 2>/dev/null \
    | tr -d '\r' | grep -vE '^\*|Warning'

pan_leak="$(audit "SELECT COUNT(*) FROM payorch_vault.vault_access_log
                   WHERE token LIKE '%4242424242424242%'
                      OR purpose LIKE '%4242%' OR reference LIKE '%4242424242%';")"
check "no PAN anywhere in the audit log" "0" "${pan_leak}"

echo
echo "=============================================================="
[[ "${FAIL}" -eq 0 ]] && echo " PASS" || echo " FAIL - ${FAIL} check(s) failed."
echo "=============================================================="
exit "${FAIL}"
