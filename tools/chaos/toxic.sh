#!/usr/bin/env bash
#
# Drive Toxiproxy from a runbook instead of from memory.
#
#   tools/chaos/toxic.sh list
#   tools/chaos/toxic.sh latency mysql 500        # +500ms each way
#   tools/chaos/toxic.sh timeout mysql 0          # accept and never respond
#   tools/chaos/toxic.sh reset-peer mysql 0       # RST the connection
#   tools/chaos/toxic.sh bandwidth mysql 100      # throttle to 100 KB/s
#   tools/chaos/toxic.sh clear mysql
#   tools/chaos/toxic.sh clear-all
#
# Every experiment starts with `clear-all`. Leaving a toxic on from the previous
# run is the fastest way to produce a graph that means nothing, and it is
# invisible - the run completes, the numbers look interesting, and they are
# describing two faults at once.

set -euo pipefail

API="${TOXIPROXY_API:-http://localhost:8474}"
COMMAND="${1:-list}"

require_proxy() {
    if [[ -z "${1:-}" ]]; then
        echo "usage: $0 $COMMAND <proxy> [value]   (proxies: mysql, redis)" >&2
        exit 2
    fi
}

# Toxiproxy applies a toxic in one direction at a time. `downstream` is data
# flowing from the database back to the service, which is where a slow query
# actually shows up; `upstream` would delay the request on its way out. For a
# latency toxic the distinction rarely changes the total, but it does change
# what a packet capture looks like, so it is worth being deliberate.
# `tr -d '\r'` after every python call, and it is not optional on Windows.
#
# Python's stdout is in text mode, so on Windows `print` writes CRLF. Under Git
# Bash the CR survives into the shell variable, and the failure it causes is a
# genuinely baffling one: the name looks correct in every echo, and the DELETE
# goes to `.../toxics/latency_downstream\r`, which Toxiproxy answers with a 404
# that `curl -sf` swallows. The toxic silently stays applied and the next
# experiment runs with two faults active.
strip_cr() {
    tr -d '\r'
}

list_proxies() {
    curl -sf "${API}/proxies" \
        | python -c "import sys,json; print('\n'.join(json.load(sys.stdin).keys()))" \
        | strip_cr
}

clear_proxy() {
    local proxy=$1
    local names
    names=$(curl -sf "${API}/proxies/${proxy}/toxics" \
        | python -c "import sys,json; print('\n'.join(t['name'] for t in json.load(sys.stdin)))" \
        | strip_cr)

    if [[ -z "$names" ]]; then
        echo "${proxy}: no toxics"
        return
    fi
    while read -r toxic; do
        [[ -z "$toxic" ]] && continue
        curl -sf -X DELETE "${API}/proxies/${proxy}/toxics/${toxic}" >/dev/null
        echo "${proxy}: removed ${toxic}"
    done <<< "$names"
}

add_toxic() {
    local proxy=$1 name=$2 type=$3 stream=$4 attrs=$5

    # Replace rather than add. Toxiproxy answers 409 when a toxic of the same
    # name already exists, and `curl -f` turns that into silent empty output -
    # so re-running `toxic.sh latency mysql 1000` after `... 500` would appear
    # to succeed while leaving 500ms in place. For a runbook helper, "set it to
    # this" is the only sane meaning.
    curl -s -o /dev/null -X DELETE "${API}/proxies/${proxy}/toxics/${name}"

    curl -sf -X POST "${API}/proxies/${proxy}/toxics" \
        -H 'Content-Type: application/json' \
        -d "{\"name\":\"${name}\",\"type\":\"${type}\",\"stream\":\"${stream}\",\"toxicity\":1.0,\"attributes\":${attrs}}" \
        | python -m json.tool
}

case "$COMMAND" in
    list)
        curl -sf "${API}/proxies" | python -m json.tool
        ;;

    latency)
        require_proxy "${2:-}"
        add_toxic "$2" "latency_downstream" "latency" "downstream" \
            "{\"latency\":${3:-500},\"jitter\":0}"
        ;;

    timeout)
        # The one that finds missing connection timeouts: the proxy accepts the
        # connection and then never sends anything back, so the client waits
        # forever rather than being refused.
        require_proxy "${2:-}"
        add_toxic "$2" "timeout_downstream" "timeout" "downstream" \
            "{\"timeout\":${3:-0}}"
        ;;

    reset-peer)
        # An RST rather than a clean close. Different from `timeout` in the way
        # that matters: the client learns immediately, so this finds code that
        # assumes a connection failure is retryable when the request may already
        # have been executed.
        require_proxy "${2:-}"
        add_toxic "$2" "reset_peer_downstream" "reset_peer" "downstream" \
            "{\"timeout\":${3:-0}}"
        ;;

    bandwidth)
        require_proxy "${2:-}"
        add_toxic "$2" "bandwidth_downstream" "bandwidth" "downstream" \
            "{\"rate\":${3:-100}}"
        ;;

    clear)
        require_proxy "${2:-}"
        clear_proxy "$2"
        ;;

    clear-all)
        # Written as a loop over a function rather than by re-invoking this
        # script per proxy. Self-invocation via "$0" depends on how the script
        # was launched and on the working directory, and when it goes wrong it
        # fails inside a subshell where `set -e` cannot see it - so `clear-all`
        # reports success while leaving a toxic in place. A stale toxic is the
        # worst possible failure for this tool: the next experiment runs with
        # two faults active and nothing says so.
        for proxy in $(list_proxies); do
            clear_proxy "$proxy"
        done
        echo "all toxics cleared"
        ;;

    *)
        echo "unknown command: $COMMAND" >&2
        echo "usage: $0 {list|latency|timeout|reset-peer|bandwidth|clear|clear-all} <proxy> [value]" >&2
        exit 2
        ;;
esac
