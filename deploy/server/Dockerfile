# syntax=docker/dockerfile:1
#
# Package the pre-built Saigon Social API server on a small JRE.
#
# NOTE: the fat JAR is built on the host by ./deploy/deploy-server.sh
# (Gradle's :server:buildFatJar) and staged into ./deploy/server/dist before this image is built.
# We deliberately do NOT compile inside Docker: a multi-stage build downloads the entire Gradle
# distribution and recompiles the whole project from scratch on every deploy (10+ minutes — see the
# slow `Downloading gradle-*.zip` / `:server:buildFatJar` step), whereas the host already has a warm
# Gradle and build cache, so building there takes seconds on a re-run.
#
# Build context for this Dockerfile is ./deploy/server (see deploy-server.sh).
FROM eclipse-temurin:21-jre
WORKDIR /app

# The Ktor `buildFatJar` task emits `server-all.jar`; deploy-server.sh stages it here as server.jar.
COPY dist/server.jar /app/server.jar

# Saigon Social binds to 0.0.0.0:9090 by default (see Application.kt).
ENV PORT=9090 \
    HOST=0.0.0.0
EXPOSE 9090

ENTRYPOINT ["java", "-jar", "/app/server.jar"]
