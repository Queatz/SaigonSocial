# syntax=docker/dockerfile:1
#
# Package the pre-built Saigon Social web client behind nginx.
#
# NOTE: the Compose/JS bundle is built on the host by ./deploy/deploy-web.sh
# (Gradle's :app:webApp:jsBrowserDistribution) and staged into ./deploy/web/dist
# before this image is built. We deliberately do NOT compile the Kotlin web target inside
# Docker: the Gradle tooling downloads its own Node/yarn, which is awkward in a minimal
# container and makes the build painfully slow.
#
# Build context for this Dockerfile is ./deploy/web (see deploy-web.sh).
FROM nginx:alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html/

EXPOSE 80
