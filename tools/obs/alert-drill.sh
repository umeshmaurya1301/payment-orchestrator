#!/usr/bin/env bash
#
# The phase-4 exit criterion, as a script:
#
#     "Alerts firing during a chaos run and resolving after."
#     "An alert that never fires during a chaos run is not configured correctly."
#
#   tools/obs/alert-drill.sh
#
# WHY A DEDICATED SCRIPT AND NOT run-experiment.sh
#
# The load-test runner is built for measuring a system. This measures the
# WATCHER, and the two want opposite things from the clock. An experiment wants
# the shortest run that produces a stable number; an alert drill has to outlast
# the alerting pipeline's own latency or it will conclude "no alerts fired" from
# a system that was about to fire four.
#
# THE PIPELINE'S LATENCY IS ~8 MINUTES, AND IT IS NOT ADVERTISED
#
# Measured from SigNoz's own evaluation log:
#
#     eval_window: 300000     the 5m the rule looks back over
#     eval_delay:  120000     a 2m lag BEFORE that window even starts
#
# So a rule evaluating at 16:35 is judging 16:28-16:33. The first version of
# this drill waited 420s after clearing chaos, concluded provider-p99-breach had
# not resolved, and was wrong - the last breaching sample was at 16:28, and the
# window containing it was still under evaluation until ~16:35. The resolved
# notification for breaker-open arrived at 16:34:43, twenty-two seconds after
# the drill printed its verdict.
#
# eval_delay is the part that catches people. Nothing in the UI mentions it, and
# it turns "5 minute window" into "eight minutes before a resolution is visible".
# RECOVERY_SECONDS is 600 for that reason and should not be shortened.
#
# THREE CHAOS PHASES, AND WHY ONE IS NOT ENOUGH
#
# The first version used a single profile - latency 2000ms and errorRate 0.6
# together - and half the alerts could not fire. Measured on psp-connector
# during that run:
#
#     payorch_bulkhead_permitted_total            824
#     payorch_bulkhead_rejected_total           2,815     <- 77% shed
#     payorch_breaker_failure_rate              15.08     <- threshold is 50
#     payorch_ratelimit_rejected_total{egress}      0
#
# The bulkhead shed 77% of calls, and a bulkhead rejection is not a provider
# fault, so the breaker only judged the 824 that got through and stayed closed
# through a run in which the provider was failing 60% of the time.
#
# That is experiment 06's lesson from the other side: widening a limit hands the
# constraint to the next layer down, and tightening one hides the next layer
# down from the evidence. A drill built on one fault would have "demonstrated"
# two alerts and quietly left two unexercised - the exact state the phase plan
# warns about, arrived at while believing the opposite.
#
#   A. fast failures    errorRate 0.9, no latency
#      Calls complete quickly, the bulkhead does not saturate, and the breaker
#      sees real provider faults.
#        -> breaker-open, payment-failure-rate
#
#   B. egress pressure  no chaos, mockpsp's contract lowered to 20 TPS
#      This one cannot be produced by load at all, and finding out why took two
#      attempts. The egress limiter sits INSIDE the bulkhead by design ("a token
#      is spent if and only if a request actually goes out"), so the bulkhead
#      binds first - and mockpsp's 20 permits at ~150ms is a ceiling of ~133 TPS
#      against a 200 TPS contract. Widening the bulkhead to 300 changed nothing:
#      egress rejections stayed at 0 with 12,377 calls permitted, because the
#      real ceiling is three services upstream:
#
#          edge - per-merchant       50 TPS   <- binds, always, first
#          edge - per-endpoint      150 TPS
#          connector - bulkhead    ~133 TPS
#          connector - egress       200 TPS   <- unreachable
#
#      Every layer is tighter than the one it protects. That is defence in depth
#      working correctly, and it means the innermost limiter can never be
#      reached through the front door. So this alert is not a load alarm - it is
#      a MISCONFIGURATION alarm, for when somebody widens the front door without
#      checking the contract behind it, or a provider lowers the contract under
#      us. The phase creates that condition honestly, with one UPDATE, mid-run,
#      no restart: 3f's dynamic config used as an instrument.
#        -> egress-limiter-saturation
#
#   C. slow success     latencyMs 2500, no errors
#      Calls succeed but crawl. Little's law caps throughput, the bulkhead
#      saturates, and the rolling P99 breaks its contract.
#        -> provider-p99-breach
#
# If an alert still does not fire, that is the interesting result and the script
# says so rather than exiting 0 with a shrug.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
UI="http://localhost:3301"
SIM="http://localhost:8085"
OUT="${ROOT}/tools/loadtest/results/07-alert-drill"

PHASE_SECONDS="${PHASE_SECONDS:-180}"
# 900s, and every second is accounted for. A condition stops matching at
# chaos-off + 5m eval_window + 2m eval_delay; the resolved NOTIFICATION then
# lags a further ~2.5m behind that (measured: breaker-open's condition cleared
# at 16:48:10 and its webhook arrived at 16:50:40). At 600s the last alert to
# fire had not resolved before the drill gave up on it.
RECOVERY_SECONDS="${RECOVERY_SECONDS:-900}"
MAX_RATE="${MAX_RATE:-300}"
BASELINE_TIMEOUT="${BASELINE_TIMEOUT:-900}"

# mockpsp's committed contract, restored on the way out. If this script is
# interrupted, restarting the connector does NOT fix it - the value lives in
# psp_config, so re-run the UPDATE by hand or re-apply V5.
EGRESS_NORMAL=200
EGRESS_TIGHT=20

mkdir -p "$OUT"

expected=(breaker-open provider-p99-breach payment-failure-rate egress-limiter-saturation)

# --- SigNoz access (see signoz.sh for why it looks like this) ---------------
org_id() {
    docker exec signoz-metastore-postgres-0 \
        psql -U signoz -d signoz -t -A -c "select id from organizations limit 1" 2>/dev/null | tr -d '\r\n'
}
TOKEN="$(curl -s -X POST "${UI}/api/v2/sessions/email_password" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${SIGNOZ_ADMIN_EMAIL:-admin@payorch.local}\",\"password\":\"${SIGNOZ_ADMIN_PASSWORD:-Payorch!Local1}\",\"orgID\":\"$(org_id)\"}" \
    | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null | tr -d '\r\n')"

if [[ -z "${TOKEN}" ]]; then
    echo "could not authenticate to ${UI} - is SigNoz up?" >&2
    exit 2
fi

rule_states() {
    curl -s -H "Authorization: Bearer ${TOKEN}" "${UI}/api/v1/rules" | python -c "
import sys,json
for r in sorted(json.load(sys.stdin).get('data',{}).get('rules',[]), key=lambda r: r.get('alert','')):
    print('%-28s %s' % (r.get('alert'), r.get('state') or 'inactive'))
"
}

firing_count() {
    curl -s -H "Authorization: Bearer ${TOKEN}" "${UI}/api/v1/rules" | python -c "
import sys,json
rs=json.load(sys.stdin).get('data',{}).get('rules',[])
print(sum(1 for r in rs if (r.get('state') or 'inactive') != 'inactive'))
"
}

set_chaos() {
    curl -s -X POST "${SIM}/_chaos" -H "Content-Type: application/json" \
        -d "{\"latencyMs\":${1},\"errorRate\":${2},\"hangRate\":0,\"duplicateRate\":0}" >/dev/null
}

set_egress() {
    docker exec payorch-mysql mysql -upayorch -ppayorch -D payorch \
        -e "UPDATE psp_config SET egress_tps = ${1} WHERE psp_id = 'mockpsp';" 2>/dev/null
    echo "     mockpsp egress_tps -> ${1} (psp-connector polls every 2s)"
}

banner() { echo; echo "=== $* ==="; }

cleanup() {
    curl -s -X DELETE "${SIM}/_chaos" >/dev/null 2>&1
    set_egress "${EGRESS_NORMAL}" >/dev/null 2>&1
    docker kill payorch-drill-a payorch-drill-b payorch-drill-c >/dev/null 2>&1
}
trap cleanup EXIT

K6_PID=""
load() {
    local tag=$1 latency=$2 errors=$3
    MSYS_NO_PATHCONV=1 docker run --rm --name "payorch-drill-${tag}" \
        --network payorch_payorch \
        -v "$(cd "$ROOT" && pwd -W)/tools/loadtest:/scripts" \
        -v "$(cd "$OUT" && pwd -W):/out" \
        -e EDGE=http://payments-edge:8080 \
        -e SIMULATOR=http://mock-psp-simulator:8085 \
        -e MAX_RATE="${MAX_RATE}" \
        -e STAGE_DURATION="$((PHASE_SECONDS / 3))s" \
        -e CHAOS_LATENCY_MS="${latency}" \
        -e CHAOS_ERROR_RATE="${errors}" \
        grafana/k6:latest run --summary-export="/out/summary-${tag}.json" /scripts/ramp.js \
        > "${OUT}/k6-${tag}.log" 2>&1 &
    K6_PID=$!
}

stop_load() {
    docker kill "payorch-drill-$1" >/dev/null 2>&1
    kill "$K6_PID" 2>/dev/null
    wait "$K6_PID" 2>/dev/null
}

watch_for() {
    local seconds=$1 deadline
    deadline=$(( $(date +%s) + seconds ))
    while [[ $(date +%s) -lt $deadline ]]; do
        sleep 30
        echo "  [$(date -u +%H:%M:%S)]"
        rule_states | sed 's/^/    /'
    done
}

# ---------------------------------------------------------------------------
banner "0. baseline"
cleanup
echo "waiting for every rule to be inactive before starting"
echo "(a drill that begins with something already firing cannot show it fired BECAUSE of the drill)"

baseline_deadline=$(( $(date +%s) + BASELINE_TIMEOUT ))
while [[ "$(firing_count)" != "0" ]]; do
    if [[ $(date +%s) -ge $baseline_deadline ]]; then
        echo
        echo "TIMED OUT waiting for a clean baseline after ${BASELINE_TIMEOUT}s. Still firing:" >&2
        rule_states | sed 's/^/  /' >&2
        echo "Refusing to run - the result would not be attributable to this drill." >&2
        exit 3
    fi
    echo "  [$(date -u +%H:%M:%S)] still settling ($(firing_count) firing)"
    sleep 30
done

DRILL_START="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "clean baseline at ${DRILL_START}"
rule_states | sed 's/^/  /'
SINK_MARK="$(docker logs payorch-alert-sink 2>&1 | wc -l | tr -d ' ')"

# ---------------------------------------------------------------------------
banner "A. fast failures: errorRate 0.9, no added latency, ${MAX_RATE} rps for ${PHASE_SECONDS}s"
echo "     targets: breaker-open, payment-failure-rate"
set_chaos 0 0.9
CHAOS_ON="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
load a 0 0.9
watch_for "${PHASE_SECONDS}"
stop_load a

# ---------------------------------------------------------------------------
banner "B. egress pressure: mockpsp's contract cut to ${EGRESS_TIGHT} TPS, no chaos, ${MAX_RATE} rps for ${PHASE_SECONDS}s"
echo "     target: egress-limiter-saturation"
echo "     (the edge admits ~50 TPS per merchant; against a ${EGRESS_TIGHT} TPS contract the egress limiter must shed)"
set_chaos 0 0
set_egress "${EGRESS_TIGHT}"
load b 0 0
watch_for "${PHASE_SECONDS}"
stop_load b
set_egress "${EGRESS_NORMAL}"

# ---------------------------------------------------------------------------
banner "C. slow success: latency 2500ms, no errors, ${MAX_RATE} rps for ${PHASE_SECONDS}s"
echo "     target: provider-p99-breach (and bulkhead saturation on the dashboard)"
set_chaos 2500 0
load c 2500 0
watch_for "${PHASE_SECONDS}"
stop_load c

# ---------------------------------------------------------------------------
banner "D. chaos off, waiting ${RECOVERY_SECONDS}s for resolution"
curl -s -X DELETE "${SIM}/_chaos" >/dev/null
CHAOS_OFF="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "chaos cleared ${CHAOS_OFF}"
echo "(5m evaluation window + 2m eval_delay: nothing can resolve for at least 7 minutes)"
watch_for "${RECOVERY_SECONDS}"

# ---------------------------------------------------------------------------
banner "E. result"
echo "drill window: ${DRILL_START} -> $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "  chaos on : ${CHAOS_ON}  (phases A, B, C)"
echo "  chaos off: ${CHAOS_OFF}"

echo
echo "--- final rule states (SigNoz) ---"
rule_states | sed 's/^/  /'

# Only notifications from THIS drill. Everything before SINK_MARK belongs to a
# previous run, and counting it would let a stale success mask a fresh failure.
docker logs payorch-alert-sink 2>&1 | tail -n "+$((SINK_MARK + 1))" > "${OUT}/sink.jsonl"

echo
echo "--- notifications delivered during this drill (webhook sink) ---"
python - "${OUT}/sink.jsonl" <<'PY' | tee "${OUT}/notifications.txt"
import json,sys
rows=[]
for line in open(sys.argv[1], encoding='utf-8'):
    line=line.strip()
    if not line.startswith('{'): continue
    try: d=json.loads(line)
    except Exception: continue
    if d.get('status') in ('firing','resolved'):
        for n in (d.get('names') or ['?']):
            rows.append((d.get('received',''), d['status'], n))
for r in sorted(rows):
    print('  %-9s %-28s %s' % (r[1], r[2], r[0]))
if not rows: print('  NOTHING DELIVERED')
PY

echo
echo "--- exit criterion ---"
# TWO THINGS THIS GETS RIGHT THAT THE FIRST VERSION DID NOT.
#
# 1. The file argument, not a pipe. `python - <<'PY'` takes the SCRIPT from
#    stdin, so a piped `docker logs` never reaches sys.stdin - the first version
#    read an empty stream and reported "never fired" for four alerts the line
#    above had just printed as delivered. A verifier that cannot fail loudly is
#    worse than no verifier, because it turns an unknown into a confident no.
#
# 2. Firing and resolved are paired by startsAt, not by name. An alert instance
#    that began BEFORE this drill can resolve during it, and matching on name
#    alone credited that stale resolution to this drill's firing - which is how
#    provider-p99-breach was scored "fired and resolved" on the strength of a
#    resolution belonging to the previous run's instance. startsAt identifies
#    the instance, and Alertmanager repeats it unchanged in the resolved
#    notification.
python - "${OUT}/sink.jsonl" "${expected[@]}" <<'PY'
import json,sys
path, expected = sys.argv[1], sys.argv[2:]
fired, resolved = {}, {}          # name -> set of startsAt
for line in open(path, encoding='utf-8'):
    line=line.strip()
    if not line.startswith('{'): continue
    try: d=json.loads(line)
    except Exception: continue
    status = d.get('status')
    if status not in ('firing','resolved'): continue
    bucket = fired if status=='firing' else resolved
    starts = [s for s in (d.get('startsAt') or []) if s]
    for n in (d.get('names') or []):
        bucket.setdefault(n, set()).update(starts)
ok=True
for a in expected:
    f = fired.get(a, set())
    # Only a resolution of an instance THIS drill saw fire counts.
    r = f & resolved.get(a, set())
    good = bool(f) and bool(r)
    ok = ok and good
    print('  %s %-28s %-12s %s' % (
        'ok ' if good else 'XX ', a,
        'FIRED' if f else 'never fired',
        'resolved' if r else ('NOT resolved' if f else '-')))
print()
print('  PASS - every alert fired and resolved' if ok else
      '  INCOMPLETE - see above. An alert that never fires is not configured.')
sys.exit(0 if ok else 1)
PY
STATUS=$?

echo
echo "artefacts in ${OUT}"
exit "${STATUS}"
