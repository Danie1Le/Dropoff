# One image recipe for merchant, order and dispatch (M6-3, ticket #114). The build context is the service's directory,
# JAR names its boot jar and PORT its HTTP port. Build the jar on the host first, then the image:
#   ./gradlew :services:merchant:bootJar
#   docker build -f ops/docker/service.Dockerfile --build-arg JAR=merchant.jar --build-arg PORT=8081 \
#       -t ghcr.io/danie1le/dropoff-merchant:local services/merchant
# `docker compose --profile services build` does the second step for all three services, with the args in compose.yaml.

# Both stages use the same Alpine release, so the linked runtime runs against the musl it was built with.
ARG ALPINE_VERSION=3.24

# Stage 1 splits the fat jar into Spring Boot's layers (dependencies, the loader, snapshot dependencies, our code)
# and links a Java runtime with only the modules Spring Boot, Hibernate and Kafka need. The full
# eclipse-temurin:21-jre-alpine runtime is 165 MB on its own, which leaves little room under the 300 MB limit
# (#36, amended); this one is about 60 MB. jdk.localedata and jdk.charsets are not used by our code today: they are a
# safety net for a library that formats a non-English locale or opens a legacy charset.
FROM eclipse-temurin:21-jdk-alpine-${ALPINE_VERSION} AS build
ARG JAR
WORKDIR /build
RUN jlink --add-modules java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,jdk.charsets,jdk.crypto.ec,jdk.jfr,jdk.localedata,jdk.management,jdk.naming.dns,jdk.net,jdk.unsupported,jdk.zipfs \
    --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output jre
COPY build/libs/${JAR} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted

# Stage 2 copies the layers least likely to change first, so a code change rebuilds only the last, smallest layer.
# No compiler and a non-root user keep it small and unprivileged.
FROM alpine:${ALPINE_VERSION}
ARG PORT
ENV JAVA_HOME=/opt/java PATH=/opt/java/bin:$PATH SERVICE_PORT=${PORT}
COPY --from=build /build/jre /opt/java
RUN addgroup -S dropoff && adduser -S -G dropoff dropoff
WORKDIR /application
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
USER dropoff
EXPOSE ${PORT}
# Busybox wget ships with Alpine. The shell expands SERVICE_PORT when the check runs. Compose's `--wait` and
# `condition: service_healthy` use this check.
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=10 \
    CMD wget -q -O /dev/null "http://localhost:${SERVICE_PORT}/actuator/health" || exit 1
# Compose sets the heap with JAVA_TOOL_OPTIONS (-Xmx) to fit the container's memory limit.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
