# Simulator image (M6-3, ticket #114). Build the distribution on the host first, then the image from this directory:
#   ./gradlew :tools:simulator:installDist
#   docker build -t ghcr.io/danie1le/dropoff-simulator:local tools/simulator
# `docker compose --profile demo build` does the second step; `docker compose --profile demo up` runs it.
FROM eclipse-temurin:21-jre-alpine-3.24
# Links the package on GHCR to this repository (M6-4), so it shows on the repo page and the workflow's GITHUB_TOKEN can
# push to it.
LABEL org.opencontainers.image.source=https://github.com/Danie1Le/Dropoff
RUN addgroup -S dropoff && adduser -S -G dropoff dropoff \
    && mkdir /data && chown dropoff:dropoff /data
WORKDIR /simulator
# Libraries change less often than the entrypoint script, so they go in the lower layer.
COPY build/install/simulator/lib/ lib/
COPY --chmod=755 docker/simulator.sh simulator.sh
USER dropoff
# The state file (the seeded ids) lives in /data; Compose mounts a volume there so it outlives the container.
VOLUME /data
ENTRYPOINT ["/simulator/simulator.sh"]
CMD ["demo"]
