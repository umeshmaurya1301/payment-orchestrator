#!/usr/bin/env bash
#
# SigNoz, for phase 4.
#
#   tools/obs/signoz.sh up       # forge the compose files and start SigNoz
#   tools/obs/signoz.sh attach   # restart the payment stack pointed at it
#   tools/obs/signoz.sh apply    # push the dashboards and alert rules from files
#   tools/obs/signoz.sh alerts   # rule states, and what the sink actually received
#   tools/obs/signoz.sh status   # what is running, and whether traces arrive
#   tools/obs/signoz.sh down     # stop SigNoz, keep the data
#   tools/obs/signoz.sh destroy  # stop SigNoz and delete its volumes
#
# WHY THIS SCRIPT EXISTS
#
# SigNoz deprecated its docker-compose manifests in favour of `foundryctl`,
# which GENERATES them. That is a better fit for this project's rule about not
# vendoring somebody else's stack, and it costs one indirection: the compose
# file has to be forged before it can be run, and it lives outside this repo.
#
# Everything this project owns is in docker/signoz/ - a pinned version, a port
# remap, and the override that attaches our services. Everything SigNoz owns is
# regenerated. Upgrading is a version bump and a re-forge, not a merge.
#
# ---------------------------------------------------------------------------
# THE FIRST-RUN ACCOUNT
#
# SigNoz reports setupCompleted:false until an admin account exists, and until
# then it has no organisation - so the collector's opamp registration fails with
# `failed to find or create agent` and, this is the part that looks like a bug,
# the collector never opens port 4318 at all. Every service then reports
# "connection refused" to the ingester, which reads like a Docker networking
# fault and is a missing user record.
#
# The UI presents this as a signup form, so it looks like a manual step. It is
# not: the form POSTs /api/v1/register, and so does `up` below. The whole
# install is one command. The admin credentials are local development values,
# in the open, like the vault key and
# the merchant API keys - phase 9c is where secrets stop being literals.
# ---------------------------------------------------------------------------

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOY="${SIGNOZ_DEPLOY_DIR:-$(cd "${ROOT}/.." && pwd)/signoz-deploy}"
OVERRIDE="${ROOT}/docker/signoz/compose.override.yaml"
CASTING="${ROOT}/docker/signoz/casting.yaml"
UI="http://localhost:3301"

# Local development credentials, public by design. Change them here and in your
# password manager if this ever leaves a laptop, which it should not.
ADMIN_EMAIL="${SIGNOZ_ADMIN_EMAIL:-admin@payorch.local}"
ADMIN_PASSWORD="${SIGNOZ_ADMIN_PASSWORD:-Payorch!Local1}"

export PATH="${HOME}/.local/bin:${PATH}"

DASHBOARDS="${ROOT}/docker/signoz/dashboards"
ALERTS="${ROOT}/docker/signoz/alerts"
CHANNEL="payorch-alert-sink"

signoz_compose() {
    docker compose -f "${DEPLOY}/pours/deployment/compose.yaml" -f "${OVERRIDE}" "$@"
}

# --- API access ------------------------------------------------------------
#
# Three things about this version of SigNoz that cost time to find, recorded so
# nobody has to find them twice:
#
#   1. Login is POST /api/v2/sessions/email_password. NOT /api/v1/login - that
#      path does not exist and the SPA's catch-all answers it with 200 and an
#      HTML page, so a script that checks the status code sees success and then
#      fails to parse a token out of a web page.
#   2. It requires orgID, which is not in any API response you can reach
#      unauthenticated. It IS in the metastore, which is where this reads it.
#   3. The token field is accessToken, not accessJwt.

org_id() {
    docker exec signoz-metastore-postgres-0 \
        psql -U signoz -d signoz -t -A -c "select id from organizations limit 1" 2>/dev/null | tr -d '\r\n'
}

token() {
    local org; org="$(org_id)"
    if [[ -z "${org}" ]]; then
        echo "could not read the org id - is SigNoz up and set up?" >&2
        return 1
    fi
    curl -s -X POST "${UI}/api/v2/sessions/email_password" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\",\"orgID\":\"${org}\"}" \
        | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null | tr -d '\r\n'
}

# Strip _comment keys. The v2 dashboard API rejects unknown fields outright
# rather than ignoring them, and the rationale for every panel and threshold
# belongs next to it rather than in a wiki nobody opens.
strip_comments() {
    # encoding='utf-8' is NOT optional on Windows. Python's open() defaults to
    # the locale codepage (cp1252 here), so a UTF-8 em dash in a title is
    # decoded as two mojibake characters and POSTed that way - the API accepts
    # it and the dashboard is titled "Payments â€” Resilience" forever.
    python -c "
import json,sys
def clean(o):
    if isinstance(o,dict):  return {k:clean(v) for k,v in o.items() if not k.startswith('_')}
    if isinstance(o,list):  return [clean(v) for v in o]
    return o
json.dump(clean(json.load(open(sys.argv[1], encoding='utf-8'))), sys.stdout)
" "$1"
}

payorch_compose() {
    docker compose -f "${ROOT}/docker-compose.yml" \
                   -f "${ROOT}/docker/signoz/payorch-obs.override.yml" "$@"
}

case "${1:-status}" in

up)
    if ! command -v foundryctl >/dev/null 2>&1; then
        echo "foundryctl not found. Install it with:" >&2
        echo "  curl -fsSL https://signoz.io/foundry.sh | bash" >&2
        exit 2
    fi
    mkdir -p "${DEPLOY}"
    cp "${CASTING}" "${DEPLOY}/casting.yaml"

    echo "=== forging SigNoz compose files into ${DEPLOY} ==="
    (cd "${DEPLOY}" && foundryctl forge >/dev/null) || { echo "forge failed" >&2; exit 2; }

    echo "=== starting SigNoz ==="
    signoz_compose up -d || exit 2

    echo "=== waiting for the UI ==="
    for _ in $(seq 1 60); do
        [[ -n "$(curl -s --max-time 5 "${UI}/api/v1/version" 2>/dev/null)" ]] && break
        sleep 5
    done

    # Idempotent: a second call against an initialised SigNoz is refused, which
    # is why the result is reported rather than checked for success.
    if curl -s --max-time 10 "${UI}/api/v1/version" 2>/dev/null | grep -q '"setupCompleted":false'; then
        echo "=== creating the admin account ==="
        curl -s -X POST "${UI}/api/v1/register" \
            -H "Content-Type: application/json" \
            -d "{\"name\":\"payorch-admin\",\"orgName\":\"payorch\",\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\",\"token\":\"\",\"sourceUrl\":\"\"}" \
            >/dev/null 2>&1
    fi

    echo
    echo "SigNoz is up:  ${UI}"
    echo "  sign in as   ${ADMIN_EMAIL} / ${ADMIN_PASSWORD}"
    echo "  then         $0 attach"
    ;;

attach)
    if ! docker network inspect signoz-network >/dev/null 2>&1; then
        echo "signoz-network does not exist - run '$0 up' first" >&2
        exit 2
    fi
    echo "=== restarting the payment stack pointed at SigNoz ==="
    payorch_compose up -d
    ;;

apply)
    # Dashboards and alerts, from files, idempotently. Existing objects with
    # the same name are DELETED and recreated rather than patched: both APIs
    # want an id for an update, tracking ids would mean a state file, and a
    # state file that disagrees with the server is a worse problem than a
    # re-created dashboard.
    T="$(token)" || exit 2
    if [[ -z "${T}" ]]; then echo "could not authenticate to ${UI}" >&2; exit 2; fi

    api() { curl -s -H "Authorization: Bearer ${T}" -H "Content-Type: application/json" "$@"; }

    # --- the notification channel ------------------------------------------
    # SigNoz refuses to create a rule with no channel, and this stack has no
    # Slack workspace. The webhook points at payorch-alert-sink, which logs
    # each notification as one JSON line - see docker/signoz/alert-sink.
    if api "${UI}/api/v1/channels" | grep -q "\"name\":\"${CHANNEL}\""; then
        echo "=== channel ${CHANNEL} already exists ==="
    else
        echo "=== creating channel ${CHANNEL} ==="
        api -X POST "${UI}/api/v1/channels" -d "{
            \"name\": \"${CHANNEL}\",
            \"webhook_configs\": [{\"send_resolved\": true, \"url\": \"http://payorch-alert-sink:9095/\"}]
        }" | python -c "import sys,json;d=json.load(sys.stdin);print('   ',d.get('status'))"
    fi

    # --- dashboards ---------------------------------------------------------
    for f in "${DASHBOARDS}"/*.json; do
        [[ -e "$f" ]] || continue
        # The slug, not the display title - it is what the list endpoint
        # returns and the only field guaranteed to be ASCII.
        name="$(python -c "import json,sys;print(json.load(open(sys.argv[1], encoding='utf-8'))['name'])" "$f")"
        # data.dashboards, not data - the list endpoint wraps the array in an
        # object carrying total/tags/reservedKeywords alongside it.
        for id in $(api "${UI}/api/v2/dashboards" | python -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception: sys.exit()
for x in ((d.get('data') or {}).get('dashboards') or []):
    if x.get('name')==sys.argv[1]:
        print(x.get('id',''))
" "$name"); do
            api -X DELETE "${UI}/api/v2/dashboards/${id}" >/dev/null
        done
        echo "=== dashboard: ${name} ==="
        strip_comments "$f" | api -X POST "${UI}/api/v2/dashboards" -d @- \
            | python -c "import sys,json;d=json.load(sys.stdin);print('   ',d.get('status',d))" 2>/dev/null \
            || echo "    FAILED"
    done

    # --- alert rules --------------------------------------------------------
    for f in "${ALERTS}"/*.json; do
        [[ -e "$f" ]] || continue
        name="$(python -c "import json,sys;print(json.load(open(sys.argv[1], encoding='utf-8'))['alert'])" "$f")"
        for id in $(api "${UI}/api/v1/rules" | python -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception: sys.exit()
for r in d.get('data',{}).get('rules',[]):
    if r.get('alert')==sys.argv[1]: print(r['id'])
" "$name"); do
            api -X DELETE "${UI}/api/v1/rules/${id}" >/dev/null
        done
        echo "=== alert: ${name} ==="
        strip_comments "$f" | api -X POST "${UI}/api/v1/rules" -d @- \
            | python -c "
import sys,json
d=json.load(sys.stdin)
print('   ', d.get('status') if d.get('status')=='success' else d)
" 2>/dev/null || echo "    FAILED"
    done
    ;;

alerts)
    # What is configured, what state each rule is in, and what actually got
    # delivered. The last column is the one that matters: SigNoz believing an
    # alert fired and a notification arriving are different claims, and only
    # the sink can settle the second one.
    T="$(token)" || exit 2
    echo "=== rules ==="
    curl -s -H "Authorization: Bearer ${T}" "${UI}/api/v1/rules" | python -c "
import sys,json
rules=json.load(sys.stdin).get('data',{}).get('rules',[])
if not rules: print('  none configured - run \'signoz.sh apply\'')
for r in sorted(rules,key=lambda r:r.get('alert','')):
    print('  %-28s %-10s %s' % (r.get('alert'), r.get('state') or 'inactive',
                                 (r.get('labels') or {}).get('severity','')))
"
    echo
    echo "=== notifications delivered to the sink ==="
    if ! docker ps --format '{{.Names}}' | grep -q payorch-alert-sink; then
        echo "  alert-sink is not running - '$0 attach' starts it"
    else
        docker logs payorch-alert-sink 2>&1 | python -c "
import sys,json
seen=0
for line in sys.stdin:
    line=line.strip()
    if not line.startswith('{'): continue
    try: d=json.loads(line)
    except Exception: continue
    if d.get('status') in ('firing','resolved'):
        seen+=1
        print('  %-9s %-28s %s' % (d['status'], ','.join(d.get('names') or []), d.get('received','')))
if not seen: print('  nothing delivered yet')
"
    fi
    ;;

status)
    echo "=== SigNoz containers ==="
    docker ps --filter "name=signoz" --format "  {{.Names}}  {{.Status}}" | sort

    echo
    echo "=== setup ==="
    version="$(curl -s --max-time 5 "${UI}/api/v1/version" 2>/dev/null)"
    if [[ -z "${version}" ]]; then
        echo "  UI not answering on ${UI} yet"
    elif [[ "${version}" == *'"setupCompleted":false'* ]]; then
        echo "  NOT SET UP - run '$0 up' to create the admin account."
        echo "  Until it exists the collector cannot register, port 4318 stays closed,"
        echo "  and every service reports 'connection refused' to the ingester."
    else
        echo "  setup complete"
    fi

    echo
    echo "=== spans ingested in the last 15 minutes ==="
    docker exec signoz-telemetrystore-clickhouse-0-0 clickhouse-client -q "
        SELECT resource_string_service\$\$name AS service, count() AS spans
        FROM signoz_traces.distributed_signoz_index_v3
        WHERE timestamp > now() - INTERVAL 15 MINUTE
        GROUP BY service ORDER BY spans DESC
    " 2>/dev/null | sed 's/^/  /' || echo "  clickhouse not reachable"
    ;;

down)
    signoz_compose down
    ;;

destroy)
    # -v, so the next `up` starts from nothing - including the admin account,
    # which `up` then recreates.
    signoz_compose down -v
    ;;

*)
    echo "usage: $0 {up|attach|apply|alerts|status|down|destroy}" >&2
    exit 2
    ;;
esac
