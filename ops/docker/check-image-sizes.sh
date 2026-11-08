#!/usr/bin/env bash
# Fails if any Dropoff image is 300 MB or more uncompressed (M6-3, decision #36). Run after
# `docker compose --profile demo build`; CI runs it on every push and pull request.
#
# It sums the layer sizes from `docker history`, which are uncompressed on both image stores. `docker image inspect`'s
# Size is not used: the containerd store (Docker Desktop, newer engines) also counts the compressed layers in it.
set -euo pipefail

LIMIT_BYTES=$((300 * 1000 * 1000))
TAG="${DROPOFF_TAG:-local}"
failed=0

for image in merchant order dispatch simulator; do
    ref="ghcr.io/danie1le/dropoff-${image}:${TAG}"
    size=$(docker history --human=false --format '{{.Size}}' "$ref" | awk '{ total += $1 } END { printf "%d", total }')
    echo "${ref}: $((size / 1000 / 1000)) MB"
    if [ "$size" -ge "$LIMIT_BYTES" ]; then
        echo "  over the 300 MB limit" >&2
        failed=1
    fi
done

exit "$failed"
