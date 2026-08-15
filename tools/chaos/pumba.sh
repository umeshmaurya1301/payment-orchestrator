#!/usr/bin/env bash
#
# Process-level chaos. Breaks the PROCESS, not the link and not the bean.
#
#   tools/chaos/pumba.sh sigterm payorch-psp-connector
#   tools/chaos/pumba.sh kill    payorch-psp-connector
#   tools/chaos/pumba.sh pause   payorch-psp-connector 20s
#
# The three are genuinely different failures and it is worth being deliberate
# about which one an experiment uses:
#
#   sigterm  the orderly one. The JVM gets the signal, Spring runs its shutdown
#            hooks, and `server.shutdown: graceful` drains in-flight requests.
#            This is what a rolling deploy or a Kubernetes eviction looks like,
#            and it is the path phase 7's graceful-shutdown work is measured on.
#            It only reaches the JVM because the Dockerfile's ENTRYPOINT uses
#            `exec` - without that, the shell holds PID 1, swallows the signal,
#            and Docker SIGKILLs after the grace period. The failure would look
#            like "graceful shutdown does not work" rather than "the signal
#            never arrived".
#
#   kill     SIGKILL. No hooks, no drain, no final log line. In-flight requests
#            simply stop existing, which is what a hard OOM or a node failure
#            looks like to everyone else.
#
#   pause    SIGSTOP. The container is frozen, not gone - it holds its TCP
#            connections open and answers nothing. This is the cruelest of the
#            three for a caller with no timeout, and the closest process-level
#            analogue of the simulator's `hangRate`.
#
# Pumba runs as a container with the Docker socket mounted, so it needs no
# install.

set -euo pipefail

PUMBA_IMAGE="${PUMBA_IMAGE:-gaiaadm/pumba:1.1.7}"
COMMAND="${1:-}"
TARGET="${2:-}"
DURATION="${3:-20s}"

if [[ -z "$COMMAND" || -z "$TARGET" ]]; then
    echo "usage: $0 {sigterm|kill|pause|status} <container> [duration]" >&2
    echo "example: $0 sigterm payorch-psp-connector" >&2
    exit 2
fi

# MSYS_NO_PATHCONV so Git Bash does not rewrite /var/run/docker.sock into a
# Windows path. Without it the mount silently becomes a directory named after
# the Git installation and Pumba reports that it cannot reach Docker.
run_pumba() {
    MSYS_NO_PATHCONV=1 docker run --rm \
        -v /var/run/docker.sock:/var/run/docker.sock \
        "$PUMBA_IMAGE" --log-level info "$@"
}

case "$COMMAND" in
    sigterm)
        echo "sending SIGTERM to ${TARGET} - expect a graceful drain"
        run_pumba kill --signal SIGTERM "$TARGET"
        ;;

    kill)
        echo "sending SIGKILL to ${TARGET} - expect in-flight requests to vanish"
        run_pumba kill --signal SIGKILL "$TARGET"
        ;;

    pause)
        echo "pausing ${TARGET} for ${DURATION} - connections stay open, nothing answers"
        run_pumba pause --duration "$DURATION" "$TARGET"
        ;;

    status)
        docker ps --filter "name=${TARGET}" --format "table {{.Names}}\t{{.Status}}"
        ;;

    *)
        echo "unknown command: $COMMAND" >&2
        exit 2
        ;;
esac
