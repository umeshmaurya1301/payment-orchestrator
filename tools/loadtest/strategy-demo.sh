#!/usr/bin/env bash
#
# Phase 5d's exit criterion: all four routing strategies selectable per merchant
# and DEMONSTRABLY DIFFERENT.
#
#   tools/loadtest/strategy-demo.sh
#
# The phase plan calls this "the one worth the most and the one most likely to be
# skipped", and the reason it gets skipped is that implementing four strategies
# is easy while proving they differ requires the providers to disagree. If every
# provider is equally fast, equally cheap and equally healthy, all four
# strategies pick the same one and the demonstration proves nothing.
#
# So the seed data is deliberately spread across three axes that point in
# DIFFERENT directions, and the script sets a priority order for the run that
# keeps them apart:
#
#   provider   priority   cost_bps   latency   -> which strategy wants it
#   psp-c         10         195       800ms      PRIORITY      (first in order)
#   psp-b         20         120       2.5s       CHEAPEST      (cheapest)
#   psp-a         30         275       200ms      neither
#   mockpsp       40         200       ~5ms       LEAST_LATENCY (fastest)
#
# The committed priorities are NOT used, and that is the point. With them,
# mockpsp is both first in priority and the fastest provider, so PRIORITY and
# LEAST_LATENCY both pick it and two of the four strategies look identical - the
# first run of this script did exactly that. Four strategies can only be shown to
# differ if the data gives each of them a different favourite.
#
# Priorities are restored on exit, so experiments 00-09 still reproduce.
#
# Every strategy still respects the health floor. That is not a strategy's choice
# to make: "cheapest" with no floor finds the provider that fails most cheaply.

set -uo pipefail

EDGE="${EDGE:-http://localhost:8080}"
API_KEY="${API_KEY:-pk_test_dev_merchant_key}"
N="${N:-40}"

mysql_q() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

set_strategy() {
    mysql_q "UPDATE merchant SET routing_strategy = '$1'
             WHERE api_key_hash = SHA2('${API_KEY}', 256);" >/dev/null
}

restore() {
    set_strategy HEALTH_WEIGHTED
    mysql_q "UPDATE psp_config SET enabled = 1;" >/dev/null
    mysql_q "UPDATE psp_config SET priority = CASE psp_id
                 WHEN 'mockpsp' THEN 10 WHEN 'psp-a' THEN 20
                 WHEN 'psp-b' THEN 30 WHEN 'psp-c' THEN 40 END;" >/dev/null
}
trap restore EXIT

# A priority order in which the first provider is NOT also the fastest or the
# cheapest, so each strategy has a distinct favourite.
mysql_q "UPDATE psp_config SET priority = CASE psp_id
             WHEN 'psp-c' THEN 10 WHEN 'psp-b' THEN 20
             WHEN 'psp-a' THEN 30 WHEN 'mockpsp' THEN 40 END;" >/dev/null

pay_once() {
    local ref="sd-$(date +%s%N)-$RANDOM"
    curl -s --max-time 30 -o /dev/null -X POST "${EDGE}/v1/payments" \
        -H "Content-Type: application/json" \
        -H "X-Api-Key: ${API_KEY}" \
        -H "Idempotency-Key: ${ref}" \
        -d "{\"amountMinor\":4200,\"currency\":\"INR\",\"card\":{\"number\":\"4242424242424242\",\"expiryMonth\":12,\"expiryYear\":2030,\"cvv\":\"123\"},\"merchantReference\":\"${ref}\"}"
    echo "$ref"
}

# Distribution of FIRST attempts for payments tagged in this round.
distribution_since() {
    mysql_q "SELECT a.psp_id, COUNT(*)
             FROM payment p
             JOIN payment_attempt a ON a.payment_id = p.id AND a.attempt_no = 1
             WHERE p.merchant_reference LIKE 'sd-%'
               AND p.created_at >= '$1'
             GROUP BY a.psp_id
             ORDER BY COUNT(*) DESC;"
}

# Gives every provider a fresh measurement immediately before a strategy is
# measured.
#
# NOT COSMETIC. The first run of this script produced LEAST_LATENCY picking
# psp-b at a p99 of 3000ms over mockpsp at 10ms, and the code was correct: the
# CHEAPEST block before it had sent 40 sequential payments to psp-b at 2.5s
# each, taking ~100 seconds, so mockpsp's 60-second rolling window had EXPIRED.
# psp-b was the only provider left with a measurement, and unknowns sort last by
# design, so psp-b won by being the only candidate anyone had data about.
#
# That is the stale-health trap biting a strategy rather than the scorer, and it
# is a real property of every deterministic strategy here: by not exploring, they
# starve the alternatives of the traffic that would keep their measurements
# alive, and then compare against whatever they already chose. HEALTH_WEIGHTED
# does not have this problem, which is most of why it is the default.
#
# The warm-up makes the demonstration measure the strategy rather than the
# order the blocks happened to run in.
# Warms each provider by making it the ONLY enabled one in turn.
#
# The obvious warm-up - "send some payments on HEALTH_WEIGHTED" - does not work,
# and failing at it twice is what produced the note above. HEALTH_WEIGHTED
# concentrates ~76% of its traffic on the top-ranked provider, so a 12-payment
# warm-up leaves the bottom-ranked provider with zero samples and a p99 of -1.
# LEAST_LATENCY then ranks it last, being unmeasured, and picks the slowest
# provider that happens to have data. Both times the router was right and the
# warm-up was lying.
#
# Enabling one provider at a time is the only way to guarantee every provider has
# a fresh measurement, and it is honest about the cost: this is a test harness
# doing something production cannot, precisely because production has no way to
# make a starved provider prove itself. That gap is the standing question in
# experiment 09 about synthetic probes.
warm_all_providers() {
    set_strategy PRIORITY
    for psp in psp-c psp-b psp-a mockpsp; do
        mysql_q "UPDATE psp_config SET enabled = (psp_id = '${psp}');" >/dev/null
        sleep 3
        for _ in $(seq 1 3); do pay_once >/dev/null; done
    done
    mysql_q "UPDATE psp_config SET enabled = 1;" >/dev/null
    sleep 3
}

run_strategy() {
    local strategy=$1
    echo
    echo "=============================================================="
    echo " ${strategy}"
    echo "=============================================================="
    warm_all_providers
    set_strategy "$strategy"
    # The orchestrator reads the column per payment, so no restart and no wait
    # beyond the transaction - the same property 3f established for psp_config.
    local since
    since="$(mysql_q "SELECT DATE_FORMAT(NOW(3), '%Y-%m-%d %H:%i:%s.%f');")"
    sleep 1

    for _ in $(seq 1 "$N"); do pay_once >/dev/null; done
    sleep 1

    distribution_since "$since" | awk -v n="$N" '
        { printf "   %-10s %4d  %5.1f%%  ", $1, $2, 100*$2/n
          bar = int(100*$2/n/4); for (i=0;i<bar;i++) printf "#"; printf "\n" }'
}

echo "provider seed data (the spread that makes this demonstrable):"
mysql_q "SELECT psp_id, priority, cost_bps, deadline_slice_ms FROM psp_config ORDER BY priority;" \
    | awk '{printf "   %-10s priority=%-4s cost_bps=%-5s slice=%sms\n", $1,$2,$3,$4}'

echo
echo "warming every provider so the health view has an opinion about each..."
for _ in $(seq 1 20); do pay_once >/dev/null; done
sleep 3
curl -s http://localhost:8083/actuator/providerhealth | python -c "
import sys,json
for k,v in sorted(json.load(sys.stdin)['providers'].items()):
    print('   %-10s score=%3d p99=%-6s %s' % (k, v['score'], v['p99Ms'], v['reason']))
" 2>/dev/null

run_strategy PRIORITY
run_strategy CHEAPEST
run_strategy LEAST_LATENCY
run_strategy HEALTH_WEIGHTED

echo
echo "=============================================================="
echo " Read the four blocks against each other, not in isolation."
echo " PRIORITY and CHEAPEST concentrating on DIFFERENT single"
echo " providers is the demonstration; HEALTH_WEIGHTED spreading"
echo " is the fourth behaviour and the reason it is the default."
echo "=============================================================="
