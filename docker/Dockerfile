# syntax=docker/dockerfile:1
#
# Runtime-only image, shared by all six services and selected with a build arg.
#
# The jar is built on the host (`./gradlew bootJar`) rather than in a builder
# stage inside the image. A builder stage would re-download the Gradle
# distribution and the full dependency set into a layer on every cache miss,
# which on a normal home connection turns a ten-second rebuild into a ten-minute
# one. The trade-off is that `docker compose build` depends on a prior Gradle
# build - see the README.

FROM eclipse-temurin:25-jre-alpine

# Which service to package. Supplied per service in docker-compose.yml.
ARG SERVICE
RUN test -n "$SERVICE" || (echo "build arg SERVICE is required" && false)

# Run as a non-root user. addgroup/adduser are busybox builtins, so this needs
# no package installation and therefore no network during the image build.
RUN addgroup -S payorch && adduser -S -G payorch payorch

WORKDIR /app

# Exactly one jar per service: the plain jar is disabled in build.gradle.kts.
COPY --chown=payorch:payorch services/${SERVICE}/build/libs/*.jar /app/app.jar

USER payorch

# MaxRAMPercentage, not -Xmx. The container memory limit is set in
# docker-compose.yml, and this makes the heap track that limit instead of
# being pinned to a number that stops matching the moment the limit changes.
# ExitOnOutOfMemoryError so a heap-exhausted JVM dies and is restarted rather
# than limping along failing every request - which is the behaviour the chaos
# experiments from phase 2 onward need in order to mean anything.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

# `exec` so the JVM becomes PID 1 and receives SIGTERM directly. Without it the
# shell holds PID 1, swallows the signal, and Docker kills the container after
# the grace period - which would make the graceful-shutdown and Pumba SIGTERM
# work in phase 7 silently untestable.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
