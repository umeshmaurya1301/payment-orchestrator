#!/usr/bin/env bash
#
# Phase 9c: raw provider input expires, and one shape of document never does.
#
#   tools/security/settlement-retention.sh
#
# WHAT "RAW PAYLOADS" MEANS HERE, STATED PLAINLY
#
# The phase asks for "TTL enforcement on raw payloads in Mongo". This system has
# no raw-payload collection: the ledger stores structured projections of events,
# and nothing anywhere persists a raw Kafka message or a raw webhook body. So the
# criterion as literally worded has no subject, and inventing a collection in
# order to expire it would be building a component to delete it.
#
# What Mongo does hold that is genuinely raw third-party input is
# `settlement_line` - lines from a provider's settlement file, carrying their
# references and amounts. Once reconciled it is disposable and re-ingestible from
# the source file, so it is the collection a retention control belongs on.
#
# The journal deliberately has NO TTL. It is a financial record that must be kept
# for years, and a retention control applied to it would be a data-loss feature
# wearing a compliance badge.
#
# THE TRAP THIS DRILL EXISTS TO SHOW
#
# Mongo's TTL monitor expires a document when its indexed field is a DATE in the
# past. A document whose field is missing, null, or not a date is not expired -
# it is SKIPPED. Silently. Forever. A retention policy therefore fails open, and
# it fails open on exactly the documents that were written by the code path
# somebody forgot to update.

set -uo pipefail

MONGO="${MONGO:-payorch-mongo}"
DB="${DB:-payorch_ledger}"
FAIL=0

m() {
    docker exec "${MONGO}" mongosh "${DB}" --quiet --eval "$1" 2>&1 | tr -d '\r'
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

echo "=============================================================="
echo " 1. THE INDEX EXISTS - checked, not assumed"
echo "=============================================================="
# This whole section exists because the answer was NO for the entire life of the
# project. JournalEntry and SettlementLine have carried @Indexed since phases 6e
# and 8, including @Indexed(unique=true) on the event id, and Spring Data Mongo
# has defaulted auto-index-creation to false since 3.0. Every one of those
# annotations was decoration. An index asserted in an annotation and absent from
# the database is worse than no index, because the code reads as handled.
echo "   settlement_line:"
m 'db.settlement_line.getIndexes().forEach(i => print("     " + i.name + "   " + JSON.stringify(i.key) + (i.expireAfterSeconds !== undefined ? "   expireAfterSeconds=" + i.expireAfterSeconds : "")))'
echo "   journal:"
m 'db.journal.getIndexes().forEach(i => print("     " + i.name + "   " + JSON.stringify(i.key) + (i.expireAfterSeconds !== undefined ? "   expireAfterSeconds=" + i.expireAfterSeconds : "")))'

ttl="$(m 'const i = db.settlement_line.getIndexes().find(x => x.expireAfterSeconds !== undefined); print(i ? i.expireAfterSeconds : "none")')"
check "settlement_line has a TTL" "604800" "${ttl}"
check "journal deliberately has none" "none" \
    "$(m 'const i = db.journal.getIndexes().find(x => x.expireAfterSeconds !== undefined); print(i ? i.expireAfterSeconds : "none")')"

echo
echo "=============================================================="
echo " 2. THREE DOCUMENTS, THREE FATES"
echo "=============================================================="
# The TTL monitor sleeps 60s by default, which would make this drill a two-minute
# wait for a property that is either true or false. Turned down for the run and
# put back afterwards - it changes how OFTEN expiry is checked, never WHETHER a
# document is eligible, so nothing about the result depends on it.
m 'db.adminCommand({setParameter: 1, ttlMonitorSleepSecs: 3})' >/dev/null

m '
db.settlement_line.deleteMany({batchId: "ttl-drill"});
const old = new Date(Date.now() - 30 * 24 * 3600 * 1000);
db.settlement_line.insertMany([
  // Ingested 30 days ago, retention is 7 - already eligible.
  {batchId: "ttl-drill", marker: "expired",  paymentId: "x1", amountMinor: 100, ingestedAt: old},
  // Ingested now - must survive.
  {batchId: "ttl-drill", marker: "fresh",    paymentId: "x2", amountMinor: 100, ingestedAt: new Date()},
  // No ingestedAt at all. THE TRAP.
  {batchId: "ttl-drill", marker: "nofield",  paymentId: "x3", amountMinor: 100}
]);
print("     inserted " + db.settlement_line.countDocuments({batchId: "ttl-drill"}) + " drill documents");
' | grep inserted

printf "   waiting for the TTL monitor"
for _ in $(seq 1 20); do
    remaining="$(m 'print(db.settlement_line.countDocuments({batchId: "ttl-drill", marker: "expired"}))')"
    [[ "${remaining}" == "0" ]] && break
    printf "."
    sleep 3
done
echo

check "the 30-day-old line was expired" "0" \
    "$(m 'print(db.settlement_line.countDocuments({batchId: "ttl-drill", marker: "expired"}))')"
check "the fresh line survived" "1" \
    "$(m 'print(db.settlement_line.countDocuments({batchId: "ttl-drill", marker: "fresh"}))')"

echo
echo "=============================================================="
echo " 3. THE DOCUMENT THE POLICY CANNOT SEE"
echo "=============================================================="
survivor="$(m 'print(db.settlement_line.countDocuments({batchId: "ttl-drill", marker: "nofield"}))')"
check "a line with no ingestedAt is NOT expired" "1" "${survivor}"
echo "        It is not overdue, not pending, not queued. Mongo's TTL monitor"
echo "        looks for a date in an indexed field; a document without one is"
echo "        skipped, silently, forever. A retention policy fails OPEN, and it"
echo "        fails open on precisely the documents written by the code path"
echo "        somebody forgot to update - which is why ingestedAt is set in"
echo "        SettlementLine.of() rather than by whoever builds the object."

echo
echo "   How many such documents exist right now:"
m 'print("     lines with no ingestedAt: " + db.settlement_line.countDocuments({ingestedAt: {$exists: false}}) + " of " + db.settlement_line.countDocuments())'
echo "        Every line ingested before 9c added the field. They will never"
echo "        expire on their own, and a retention claim that ignores them is"
echo "        false for exactly the oldest data - the data most likely to be"
echo "        the subject of the request that prompted the policy."

echo
echo "   Cleaning up the drill and restoring the monitor interval."
m 'db.settlement_line.deleteMany({batchId: "ttl-drill"}); db.adminCommand({setParameter: 1, ttlMonitorSleepSecs: 60})' >/dev/null

echo
echo "=============================================================="
[[ "${FAIL}" -eq 0 ]] && echo " PASS" || echo " FAIL - ${FAIL} check(s) failed."
echo "=============================================================="
exit "${FAIL}"
