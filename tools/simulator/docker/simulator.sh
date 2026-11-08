#!/bin/sh
# Entrypoint of the simulator image (M6-3, ticket #114).
#   demo [options]       the default: seed if there is no state file, then run the bots until stopped
#   seed|run|smoke|watch go straight to the simulator (see Options for their flags)
# Every command gets the service URLs from MERCHANT_URL, ORDER_URL and DISPATCH_URL (the Compose service names by
# default) and keeps its state file at STATE_FILE (/data/state.json by default).
set -eu

STATE_FILE="${STATE_FILE:-/data/state.json}"

# exec replaces the shell, so Java is PID 1 and gets docker stop's SIGTERM. Seeding runs it in a subshell instead,
# so the script carries on afterwards.
simulator() {
    exec java -cp '/simulator/lib/*' io.github.danie1le.dropoff.simulator.Simulator "$@" \
        --state "$STATE_FILE" \
        --merchant-url "${MERCHANT_URL:-http://merchant:8081}" \
        --order-url "${ORDER_URL:-http://order:8082}" \
        --dispatch-url "${DISPATCH_URL:-http://dispatch:8083}"
}

if [ $# -eq 0 ] || [ "$1" = demo ]; then
    [ $# -gt 0 ] && shift
    if [ ! -f "$STATE_FILE" ]; then
        (simulator seed)
    fi
    set -- run "$@"
fi
simulator "$@"
