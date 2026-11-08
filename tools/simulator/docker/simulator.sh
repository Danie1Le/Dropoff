#!/bin/sh
# Entrypoint of the simulator image (M6-3, ticket #114).
#   demo [options]       the default: seed if there is no state file, then run the bots until stopped
#   seed|run|smoke|watch go straight to the simulator (see Options for their flags)
# Every command gets the service URLs from MERCHANT_URL, ORDER_URL and DISPATCH_URL (the Compose service names by
# default). The demo, seed and run keep their state at STATE_FILE (/data/state.json by default); smoke keeps its own at
# SMOKE_STATE_FILE (/data/smoke-state.json), so it never drives the demo bots' merchants and couriers. Flags the
# caller passes (--state, --order-url, ...) win over these defaults.
set -eu

STATE_FILE="${STATE_FILE:-/data/state.json}"
SMOKE_STATE_FILE="${SMOKE_STATE_FILE:-/data/smoke-state.json}"

# Puts the defaults right after the command (and watch's order id), then the caller's flags: Options keeps the last
# value of a repeated flag. exec replaces the shell, so Java is PID 1 and gets docker stop's SIGTERM. Seeding runs it
# in a subshell instead, so the script carries on afterwards.
simulator() {
    cmd=$1
    shift
    state="$STATE_FILE"
    [ "$cmd" = smoke ] && state="$SMOKE_STATE_FILE"
    id=
    if [ "$cmd" = watch ] && [ $# -gt 0 ]; then
        id=$1
        shift
    fi
    exec java -cp '/simulator/lib/*' io.github.danie1le.dropoff.simulator.Simulator "$cmd" ${id:+"$id"} \
        --state "$state" \
        --merchant-url "${MERCHANT_URL:-http://merchant:8081}" \
        --order-url "${ORDER_URL:-http://order:8082}" \
        --dispatch-url "${DISPATCH_URL:-http://dispatch:8083}" \
        "$@"
}

if [ $# -eq 0 ] || [ "$1" = demo ]; then
    [ $# -gt 0 ] && shift
    if [ ! -f "$STATE_FILE" ]; then
        (simulator seed)
    fi
    set -- run "$@"
fi
simulator "$@"
