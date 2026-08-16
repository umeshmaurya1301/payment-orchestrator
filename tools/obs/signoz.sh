#!/usr/bin/env bash
#
# SigNoz, for phase 4.
#
#   tools/obs/signoz.sh up       # forge the compose files and start SigNoz
#   tools/obs/signoz.sh attach   # restart the payment stack pointed at it
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
# THERE IS ONE MANUAL STEP AND IT CANNOT BE SCRIPTED.
#
# On first run, open http://localhost:3301 and create the admin account.
#
# Until that account exists SigNoz has no organisation, the collector's opamp
# registration fails with `failed to find or create agent`, and - this is the
# part that looks like a bug - the collector never opens port 4318 at all.
# Every service then reports "connection refused" to the ingester, which reads
# like a networking problem and is actually a missing user record.
# `status` below checks for it explicitly so nobody has to work that out twice.
# ---------------------------------------------------------------------------

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOY="${SIGNOZ_DEPLOY_DIR:-$(cd "${ROOT}/.." && pwd)/signoz-deploy}"
OVERRIDE="${ROOT}/docker/signoz/compose.override.yaml"
CASTING="${ROOT}/docker/signoz/casting.yaml"
UI="http://localhost:3301"

export PATH="${HOME}/.local/bin:${PATH}"

signoz_compose() {
    docker compose -f "${DEPLOY}/pours/deployment/compose.yaml" -f "${OVERRIDE}" "$@"
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

    echo
    echo "SigNoz is starting. ClickHouse takes about a minute to become healthy."
    echo
    echo "  NEXT, AND ONLY ONCE: open ${UI} and create the admin account."
    echo "  Nothing will be ingested until that exists - see the note in this script."
    ;;

attach)
    if ! docker network inspect signoz-network >/dev/null 2>&1; then
        echo "signoz-network does not exist - run '$0 up' first" >&2
        exit 2
    fi
    echo "=== restarting the payment stack pointed at SigNoz ==="
    payorch_compose up -d
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
        echo "  NOT SET UP. Open ${UI} and create the admin account."
        echo "  Until then the collector cannot register and port 4318 stays closed,"
        echo "  and every service will report 'connection refused' to the ingester."
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
    # -v, so the next `up` starts from nothing. Note this also deletes the
    # admin account, and the manual setup step comes back with it.
    signoz_compose down -v
    ;;

*)
    echo "usage: $0 {up|attach|status|down|destroy}" >&2
    exit 2
    ;;
esac
