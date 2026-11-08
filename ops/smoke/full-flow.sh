#!/usr/bin/env bash
# Full-flow smoke test across the three services (M5-4 to M5-6): places 5 orders and exits non-zero unless all are
# DELIVERED within 5 minutes. The simulator seeds first if there is no state file, then runs its own merchant and
# courier bots.
#
# Needs Compose up and every service running:
#   docker compose up -d
#   ./gradlew :services:merchant:bootRun      # merchant, port 8081 (only needed to seed)
#   ./gradlew :services:order:bootRun         # order, port 8082
#   ./gradlew :services:dispatch:bootRun      # dispatch, port 8083
# On fresh databases (after `docker compose down -v`), delete .simulator/state.json first: its ids are gone.
#
# Override the service URLs with MERCHANT_URL, ORDER_URL and DISPATCH_URL, and the run with ORDERS and TIMEOUT.
set -euo pipefail

cd "$(dirname "$0")/../.."

ORDERS="${ORDERS:-5}"
TIMEOUT="${TIMEOUT:-5m}"
MERCHANT_URL="${MERCHANT_URL:-http://localhost:8081}"
ORDER_URL="${ORDER_URL:-http://localhost:8082}"
DISPATCH_URL="${DISPATCH_URL:-http://localhost:8083}"

exec ./gradlew --quiet :tools:simulator:run --args="smoke --orders ${ORDERS} --timeout ${TIMEOUT} \
--merchant-url ${MERCHANT_URL} --order-url ${ORDER_URL} --dispatch-url ${DISPATCH_URL}"
