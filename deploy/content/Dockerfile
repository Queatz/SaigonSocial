# syntax=docker/dockerfile:1
#
# Package the pre-built Saigon Social content server on a small JRE.
#
# NOTE: the fat JAR is built on the host by ./deploy/deploy-content.sh
# (Gradle's :content:buildFatJar) and staged into ./deploy/content/dist before this image is built.
# We deliberately do NOT compile inside Docker: a multi-stage build downloads the entire Gradle
# distribution and recompiles the whole project from scratch on every deploy, whereas the host
# already has a warm Gradle and build cache (see ./deploy/server/Dockerfile for the same trade-off).
#
# Build context for this Dockerfile is ./deploy/content (see deploy-content.sh).
FROM eclipse-temurin:21-jre
WORKDIR /app

# The Ktor `buildFatJar` task emits `content-all.jar`; deploy-content.sh stages it here as content.jar.
COPY dist/content.jar /app/content.jar

# The content server binds to 0.0.0.0:9091 by default (see ContentServer.kt). In production it
# authenticates uploads against https://api.saigon.social and hands out
# https://content.saigon.social URLs (SAIGON_ENV=production); uploaded files persist in /data/content
# (mounted as a named volume by deploy-content.sh).
ENV PORT=9091 \
    HOST=0.0.0.0 \
    SAIGON_ENV=production \
    CONTENT_DIR=/data/content
EXPOSE 9091

ENTRYPOINT ["java", "-jar", "/app/content.jar"]
