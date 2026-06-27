#!/usr/bin/env bash
#
# Build, ship, and run the Saigon Social API server on its host.
#
#   Host  : api.saigon.social
#   Image : ${REGISTRY}/saigon-server:${IMAGE_TAG}
#
# IMPORTANT — how this deploys:
#   The fat JAR is BUILT ON THIS MACHINE with Gradle (:server:buildFatJar), packaged into a tiny
#   JRE image locally, then the image is SHIPPED to the remote host over SSH
#   (`docker save | ssh docker load`) and RUN there. We build on the host rather than inside Docker
#   because an in-container multi-stage build downloads the whole Gradle distribution and recompiles
#   from scratch every time (10+ minutes); the host has a warm Gradle/build cache. The app container
#   must run on the same host as the Traefik reverse proxy that terminates TLS (see
#   ./deploy/init-server.sh) and join the shared `web` Docker network, otherwise Traefik has no
#   router for api.saigon.social and Let's Encrypt never issues a certificate.
#
# The Qwen identity-generation key is injected as a runtime environment variable
# (the server reads QWEN_API_KEY via System.getenv — see Application.kt).
#
# Usage:
#   ./deploy/deploy-server.sh [user@]host        # deploy to the given host
#   SSH_HOST=root@1.2.3.4 ./deploy/deploy-server.sh
#   LOCAL=true ./deploy/deploy-server.sh         # build & run on THIS machine (app+Traefik co-located)
#
set -euo pipefail

# ---- Configuration ------------------------------------------------------------------------------
REGISTRY="${REGISTRY:-ghcr.io/saigonsocial}"
IMAGE_NAME="${IMAGE_NAME:-saigon-server}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
IMAGE="${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"

DOMAIN="${DOMAIN:-api.saigon.social}"
CONTAINER_NAME="${CONTAINER_NAME:-saigon-server}"
DOCKER_NETWORK="${DOCKER_NETWORK:-web}"   # shared network created by ./deploy/init-server.sh
CERT_RESOLVER="${CERT_RESOLVER:-letsencrypt}"   # must match ./deploy/init-server.sh
APP_PORT="${APP_PORT:-9090}"              # container port the Ktor server listens on

# Where to deploy. By default we ship the image to the remote host over SSH and run it there.
# Set LOCAL=true to build and run on this machine instead (app + Traefik on the same box).
SSH_HOST="${1:-${SSH_HOST:-root@${DOMAIN}}}"
SSH_OPTS="${SSH_OPTS:--o StrictHostKeyChecking=accept-new}"
LOCAL="${LOCAL:-false}"

# Resources / secrets baked into the running container.
QWEN_API_KEY="${QWEN_API_KEY:-sk-6c257c3e09b349bf81c631f31ef6679a}"
# Override the dev JWT secret for production (defaults to dev-secret-change-me in the server).
JWT_SECRET="${JWT_SECRET:-}"

# Resolve the repository root so the script works from any directory.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

# ---- Docker access (local build) ----------------------------------------------------------------
# Pick a working Docker invocation for the LOCAL build: plain `docker`, or `sudo docker` if the
# current user can't reach the daemon socket (e.g. not in the `docker` group). Override with DOCKER=.
if [ -n "${DOCKER:-}" ]; then
  :
elif docker info >/dev/null 2>&1; then
  DOCKER="docker"
elif command -v sudo >/dev/null 2>&1 && sudo docker info >/dev/null 2>&1; then
  echo ">> Current user can't reach the Docker daemon; falling back to 'sudo docker'."
  DOCKER="sudo docker"
else
  echo "ERROR: cannot access the Docker daemon." >&2
  echo "       Add your user to the 'docker' group (newgrp docker / re-login) or run with sudo:" >&2
  echo "         sudo usermod -aG docker \"$USER\"" >&2
  exit 1
fi

# ---- Compile the fat JAR on the host --------------------------------------------------------------
# Build the self-contained server JAR with Gradle, then stage it next to the Dockerfile (outside any
# build/ dir so .dockerignore doesn't strip it) to use as a tiny Docker build context.
JAR_SRC="server/build/libs/server-all.jar"
DIST_STAGE="deploy/server/dist"

echo ">> Building server fat JAR with Gradle (:server:buildFatJar)"
chmod +x ./gradlew
./gradlew --no-daemon :server:buildFatJar

if [ ! -f "${JAR_SRC}" ]; then
  echo "ERROR: expected build output not found at ${JAR_SRC}" >&2
  exit 1
fi

echo ">> Staging server JAR into ${DIST_STAGE}"
rm -rf "${DIST_STAGE}"
mkdir -p "${DIST_STAGE}"
cp "${JAR_SRC}" "${DIST_STAGE}/server.jar"

# ---- Build --------------------------------------------------------------------------------------
echo ">> Building server image ${IMAGE}"
${DOCKER} build \
  -f deploy/server/Dockerfile \
  -t "${IMAGE}" \
  deploy/server

# ---- Render the `docker run` command shared by local & remote -----------------------------------
# Optional extra env (JWT secret) only added when provided.
EXTRA_ENV=""
if [ -n "${JWT_SECRET}" ]; then
  EXTRA_ENV="-e JWT_SECRET=${JWT_SECRET}"
fi

# NOTE: we deliberately do NOT publish host ports — Traefik reaches the container over the shared
# `web` Docker network. Publishing 9090 is unnecessary and risks a port clash on the host.
run_container() {
  # $1 = docker command to use (e.g. "docker" locally, or "docker" on the remote shell)
  local D="$1"
  ${D} rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
  ${D} run -d \
    --name "${CONTAINER_NAME}" \
    --restart unless-stopped \
    --network "${DOCKER_NETWORK}" \
    -e "QWEN_API_KEY=${QWEN_API_KEY}" \
    -e "PORT=${APP_PORT}" \
    -e "HOST=0.0.0.0" \
    ${EXTRA_ENV} \
    --label "traefik.enable=true" \
    --label "traefik.http.routers.saigon-server.rule=Host(\`${DOMAIN}\`)" \
    --label "traefik.http.routers.saigon-server.entrypoints=websecure" \
    --label "traefik.http.routers.saigon-server.tls.certresolver=${CERT_RESOLVER}" \
    --label "traefik.http.services.saigon-server.loadbalancer.server.port=${APP_PORT}" \
    "${IMAGE}"
}

if [ "${LOCAL}" = "true" ]; then
  # ---- Run locally ------------------------------------------------------------------------------
  echo ">> (Re)starting container ${CONTAINER_NAME} locally for ${DOMAIN}"
  run_container "${DOCKER}"
  echo ">> Server deployed locally: https://${DOMAIN} (container '${CONTAINER_NAME}')"
  exit 0
fi

# ---- Ship the image to the remote host over SSH -------------------------------------------------
echo ">> Shipping image to ${SSH_HOST} (docker save | ssh docker load)"
${DOCKER} save "${IMAGE}" | gzip | ssh ${SSH_OPTS} "${SSH_HOST}" 'gunzip | docker load'

# ---- Run the container on the remote host -------------------------------------------------------
echo ">> (Re)starting container ${CONTAINER_NAME} on ${SSH_HOST} for ${DOMAIN}"
ssh ${SSH_OPTS} "${SSH_HOST}" \
  IMAGE="${IMAGE}" \
  CONTAINER_NAME="${CONTAINER_NAME}" \
  DOCKER_NETWORK="${DOCKER_NETWORK}" \
  DOMAIN="${DOMAIN}" \
  APP_PORT="${APP_PORT}" \
  CERT_RESOLVER="${CERT_RESOLVER}" \
  QWEN_API_KEY="${QWEN_API_KEY}" \
  JWT_SECRET="${JWT_SECRET}" \
  'bash -se' <<'REMOTE'
set -euo pipefail

# Make sure the shared network exists (init-server.sh creates it; be defensive on re-runs).
docker network inspect "${DOCKER_NETWORK}" >/dev/null 2>&1 || docker network create "${DOCKER_NETWORK}"

EXTRA_ENV=""
if [ -n "${JWT_SECRET}" ]; then
  EXTRA_ENV="-e JWT_SECRET=${JWT_SECRET}"
fi

docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
docker run -d \
  --name "${CONTAINER_NAME}" \
  --restart unless-stopped \
  --network "${DOCKER_NETWORK}" \
  -e "QWEN_API_KEY=${QWEN_API_KEY}" \
  -e "PORT=${APP_PORT}" \
  -e "HOST=0.0.0.0" \
  ${EXTRA_ENV} \
  --label "traefik.enable=true" \
  --label "traefik.http.routers.saigon-server.rule=Host(\`${DOMAIN}\`)" \
  --label "traefik.http.routers.saigon-server.entrypoints=websecure" \
  --label "traefik.http.routers.saigon-server.tls.certresolver=${CERT_RESOLVER}" \
  --label "traefik.http.services.saigon-server.loadbalancer.server.port=${APP_PORT}" \
  "${IMAGE}"
REMOTE

echo ">> Server deployed: https://${DOMAIN} (container '${CONTAINER_NAME}' on ${SSH_HOST})"
echo ">> Verify TLS in ~30s with: ./deploy/check-tls.sh ${DOMAIN}"
