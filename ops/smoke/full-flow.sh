#!/usr/bin/env bash
# Full-flow smoke test across the three services (M5-4 to M5-6): places 5 orders and exits non-zero unless all are
# DELIVERED within 5 minutes. The simulator seeds first if there is no state file, then runs its own merchant and
# courier bots.
#
# Needs every service up on its port (merchant 8081, only needed to seed; order 8082; dispatch 8083), either as
# containers (M6-3: build the jars, then `docker compose --profile services up -d --build`) or with
# `./gradlew :services:<name>:bootRun` next to a plain `docker compose up -d`.
# This script runs the simulator on the host, so its state is .simulator/state.json in the repo. On fresh databases
# (after `docker compose down -v`), delete that file first: its ids are gone.
# `ops/smoke/full-flow.sh --compose` runs the same check inside Compose instead (M6-4; CI runs it this way after it
# builds the images): `docker compose --profile demo run --rm simulator smoke` starts the three service containers,
# waits for their healthchecks and runs the simulator image against them by service name. Its state is
# /data/smoke-state.json on the simulator-data volume (the demo bots use /data/state.json); `down -v` drops both.
# Build the jars and images first (see compose.yaml).
#
# Override the service URLs with MERCHANT_URL, ORDER_URL and DISPATCH_URL (host mode only), and the run with ORDERS and
# TIMEOUT.
set -euo pipefail

cd "$(dirname "$0")/../.."

ORDERS="${ORDERS:-5}"
TIMEOUT="${TIMEOUT:-5m}"
MERCHANT_URL="${MERCHANT_URL:-http://localhost:8081}"
ORDER_URL="${ORDER_URL:-http://localhost:8082}"
DISPATCH_URL="${DISPATCH_URL:-http://localhost:8083}"

if [ "${1:-}" = --compose ]; then
    # -T: no TTY, so it also runs in CI. The exit code is the simulator's.
    exec docker compose --profile demo run --rm -T simulator smoke --orders "${ORDERS}" --timeout "${TIMEOUT}"
fi

exec ./gradlew --quiet :tools:simulator:run --args="smoke --orders ${ORDERS} --timeout ${TIMEOUT} \
--merchant-url ${MERCHANT_URL} --order-url ${ORDER_URL} --dispatch-url ${DISPATCH_URL}"
