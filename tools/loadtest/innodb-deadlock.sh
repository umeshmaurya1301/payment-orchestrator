#!/usr/bin/env bash
#
# Phase 7e, the half that H2 cannot provide: a real InnoDB deadlock, detected by
# a real InnoDB detector, captured from SHOW ENGINE INNODB STATUS.
#
#   tools/loadtest/innodb-deadlock.sh
#
# WHY THIS EXISTS SEPARATELY FROM LedgerDeadlockTest
#
# That test reproduces the cycle deterministically and shows a consistent lock
# order removing it, which is the important half and is where the logic belongs.
# It runs against H2. H2 detects the cycle and breaks it, so the test is honest
# about the BEHAVIOUR - but H2 is not InnoDB, and the exit criterion asks for the
# artefact InnoDB produces. Writing that artefact from memory would be inventing
# evidence, so it was left missing until the stack was available.
#
# WHAT A DEADLOCK ACTUALLY NEEDS
#
# Two transactions taking the SAME two rows in OPPOSITE order, with both holding
# their first lock when they ask for their second. Nothing else about the schema
# matters, which is why this reproduces it with two `mysql` sessions and no
# application code: the point is the database's behaviour, not ours.
#
#   session A   UPDATE merchant   ...wait...   UPDATE clearing
#   session B   UPDATE clearing   ...wait...   UPDATE merchant
#
# THE LEDGER'S OWN ORDER, AND WHY IT IS SAFE
#
# LedgerPosting.legsFor returns its legs in a fixed order per state:
#
#   AUTHORIZED   merchant, clearing
#   CAPTURED     clearing, card-network
#   REVERSED     merchant, clearing
#
# Every path that touches both merchant and clearing takes them in that order, so
# no two concurrent postings can hold each other's next lock. That is the fix
# from 7e, still in place, and this script deliberately does NOT go through the
# application - it forces the opposite order by hand to show what the database
# does when the discipline is broken.

set -uo pipefail

DB="${DB:-payorch_ledger}"
USER="${DB_USER:-payorch}"
PASS="${DB_PASS:-payorch}"
HOLD="${HOLD:-3}"

FAIL=0

mysql_q() {
    docker exec payorch-mysql mysql -u"${USER}" -p"${PASS}" -D "${DB}" -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

# A whole session in one invocation, so the transaction stays open across the
# sleep. Separate `docker exec` calls would each get their own connection and
# each transaction would commit before the other started - no overlap, no
# deadlock, and a very confusing green run.
session() {
    docker exec -i payorch-mysql mysql -u"${USER}" -p"${PASS}" -D "${DB}" 2>&1 | tr -d '\r'
}

echo "=============================================================="
echo " PREFLIGHT"
echo "=============================================================="

MERCHANT="$(mysql_q "SELECT LOWER(HEX(id)) FROM ledger_account WHERE account_ref LIKE 'merchant:%' LIMIT 1;")"
CLEARING="$(mysql_q "SELECT LOWER(HEX(id)) FROM ledger_account WHERE account_ref = 'settlement:clearing' LIMIT 1;")"

if [[ -z "${MERCHANT}" || -z "${CLEARING}" ]]; then
    echo "   no ledger accounts to contend on - run a payment first" >&2
    exit 2
fi
printf "   %-22s %s\n" "merchant account" "${MERCHANT:0:16}..."
printf "   %-22s %s\n" "clearing account" "${CLEARING:0:16}..."
printf "   %-22s %s\n" "innodb_deadlock_detect" "$(mysql_q "SELECT @@innodb_deadlock_detect;")"
printf "   %-22s %ss\n" "lock hold time" "${HOLD}"

before="$(mysql_q "SELECT COUNT(*) FROM ledger_entry;")"
echo

echo "=============================================================="
echo " FORCING THE CYCLE - two sessions, opposite order"
echo "=============================================================="

# Both sessions update ONE account by zero. A zero delta still takes the
# exclusive row lock, so the cycle is real while the balances are untouched -
# this script must not move money to prove a point about locking.
session <<SQL > /tmp/deadlock-a.out 2>&1 &
SET SESSION innodb_lock_wait_timeout = 20;
BEGIN;
UPDATE ledger_account SET balance_minor = balance_minor + 0 WHERE id = UNHEX('${MERCHANT}');
SELECT SLEEP(${HOLD});
UPDATE ledger_account SET balance_minor = balance_minor + 0 WHERE id = UNHEX('${CLEARING}');
COMMIT;
SELECT 'A committed' AS result;
SQL
A=$!

session <<SQL > /tmp/deadlock-b.out 2>&1 &
SET SESSION innodb_lock_wait_timeout = 20;
BEGIN;
UPDATE ledger_account SET balance_minor = balance_minor + 0 WHERE id = UNHEX('${CLEARING}');
SELECT SLEEP(${HOLD});
UPDATE ledger_account SET balance_minor = balance_minor + 0 WHERE id = UNHEX('${MERCHANT}');
COMMIT;
SELECT 'B committed' AS result;
SQL
B=$!

wait "$A" "$B" 2>/dev/null
echo "   session A: $(grep -cE 'ERROR 1213' /tmp/deadlock-a.out) deadlock error(s)"
echo "   session B: $(grep -cE 'ERROR 1213' /tmp/deadlock-b.out) deadlock error(s)"

victims=$(( $(grep -cE 'ERROR 1213' /tmp/deadlock-a.out) + $(grep -cE 'ERROR 1213' /tmp/deadlock-b.out) ))
echo

if [[ "${victims}" -eq 1 ]]; then
    printf "   ok   %-46s %s\n" "InnoDB chose exactly one victim" "${victims}"
else
    printf "   XX   %-46s %s (expected 1)\n" "InnoDB chose exactly one victim" "${victims}"
    echo "        Both sessions may have serialised without overlapping."
    echo "        Raise HOLD and try again."
    FAIL=$((FAIL+1))
fi

echo
echo "=============================================================="
echo " SHOW ENGINE INNODB STATUS - LATEST DETECTED DEADLOCK"
echo "=============================================================="
docker exec payorch-mysql mysql -uroot -proot -e "SHOW ENGINE INNODB STATUS\G" 2>/dev/null     | tr -d '\r'     | awk '/LATEST DETECTED DEADLOCK/{on=1} on{print; n++} /^WE ROLL BACK/{if(on) exit} n>60{exit}'     | sed 's/^/   /'

echo
after="$(mysql_q "SELECT COUNT(*) FROM ledger_entry;")"
if [[ "${before}" == "${after}" ]]; then
    printf "   ok   %-46s %s\n" "no money moved to prove a point" "${after}"
else
    printf "   XX   %-46s %s -> %s\n" "no money moved to prove a point" "${before}" "${after}"
    FAIL=$((FAIL+1))
fi

echo
echo "=============================================================="
if [[ "${FAIL}" -eq 0 ]]; then
    echo " PASS - a real InnoDB deadlock, detected and rolled back, with the"
    echo "        engine's own account of it above."
else
    echo " FAIL - ${FAIL} check(s) failed."
fi
echo "=============================================================="
exit "${FAIL}"
