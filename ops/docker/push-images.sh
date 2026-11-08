#!/usr/bin/env bash
# Tags and pushes the four Dropoff images to GHCR (M6-4, decision #36). CI's publish job runs it on pushes to main and
# on v* tags, after the build job has built, size-checked, smoke-tested and integration-tested the same images. It
# never runs for pull requests.
#
#   refs/heads/main  -> sha-<short sha> (one exact build, the tag to deploy or roll back to) and main (moving)
#   refs/tags/v1.2.3 -> 1.2.3 and latest (moving)
#
# Needs GITHUB_REF, GITHUB_REF_NAME and GITHUB_SHA (set by GitHub Actions), the images tagged DROPOFF_TAG (default
# local, as `docker compose --profile demo build` tags them) and a `docker login ghcr.io`. DRY_RUN=1 prints the
# commands instead of running them, to check the tags locally:
#   DRY_RUN=1 GITHUB_REF=refs/tags/v1.2.3 GITHUB_REF_NAME=v1.2.3 GITHUB_SHA=0123456789abcdef ops/docker/push-images.sh
set -euo pipefail

REGISTRY=ghcr.io/danie1le
SOURCE_TAG="${DROPOFF_TAG:-local}"

case "${GITHUB_REF:?}" in
    refs/heads/main)
        : "${GITHUB_SHA:?}"
        tags=("sha-${GITHUB_SHA:0:7}" main)
        ;;
    refs/tags/v*)
        : "${GITHUB_REF_NAME:?}"
        tags=("${GITHUB_REF_NAME#v}" latest)
        ;;
    *)
        echo "Refusing to push from ${GITHUB_REF}: only main and v* tags publish." >&2
        exit 1
        ;;
esac

run() {
    if [ "${DRY_RUN:-0}" = 1 ]; then
        echo "$*"
    else
        "$@"
    fi
}

for image in merchant order dispatch simulator; do
    source="${REGISTRY}/dropoff-${image}:${SOURCE_TAG}"
    for tag in "${tags[@]}"; do
        run docker tag "$source" "${REGISTRY}/dropoff-${image}:${tag}"
        run docker push "${REGISTRY}/dropoff-${image}:${tag}"
    done
done
