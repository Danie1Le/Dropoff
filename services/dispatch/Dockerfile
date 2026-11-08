# dispatch-service image (M6-3, ticket #114). Build the jar on the host first, then the image from this directory:
#   ./gradlew :services:dispatch:bootJar
#   docker build -t ghcr.io/danie1le/dropoff-dispatch:local services/dispatch
# `docker compose build` does the second step for all three services.

# Stage 1 splits the fat jar into Spring Boot's layers (dependencies, the loader, snapshot dependencies, our code)
# and links a Java runtime with only the modules Spring Boot, Hibernate and Kafka need. The full
# eclipse-temurin:21-jre-alpine runtime is 165 MB on its own and pushes the image past the 300 MB limit (#36); this
# one is about 60 MB. Keep the module list in step with the other two Dockerfiles.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
RUN jlink --add-modules java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,jdk.crypto.ec,jdk.jfr,jdk.management,jdk.naming.dns,jdk.net,jdk.unsupported,jdk.zipfs \
    --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output jre
COPY build/libs/dispatch.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted

# Stage 2 is the same Alpine release as the Temurin images. It copies the layers least likely to change first, so a
# code change rebuilds only the last, smallest layer. No compiler and a non-root user keep it small and unprivileged.
FROM alpine:3.24
ENV JAVA_HOME=/opt/java PATH=/opt/java/bin:$PATH
COPY --from=build /build/jre /opt/java
RUN addgroup -S dropoff && adduser -S -G dropoff dropoff
WORKDIR /application
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
USER dropoff
EXPOSE 8083
# Compose sets the heap with JAVA_TOOL_OPTIONS (-Xmx) to fit the container's memory limit.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
