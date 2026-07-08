#!/usr/bin/env bash
#
# Build, ship, and run the Saigon Social content server on its host.
#
#   Host  : content.saigon.social
#   Image : ${REGISTRY}/saigon-content:${IMAGE_TAG}
#
# IMPORTANT — how this deploys (mirrors ./deploy/deploy-server.sh):
#   The fat JAR is BUILT ON THIS MACHINE with Gradle (:content:buildFatJar), packaged into a tiny
#   JRE image locally, then the image is SHIPPED to the remote host over SSH
#   (`docker save | ssh docker load`) and RUN there. The container must run on the same host as the
#   Traefik reverse proxy that terminates TLS (see ./deploy/init-server.sh) and join the shared
#   `web` Docker network, otherwise Traefik has no router for content.saigon.social and
#   Let's Encrypt never issues a certificate.
#
# The container runs with SAIGON_ENV=production (baked into the image), so it authenticates uploads
# against https://api.saigon.social and hands out https://content.saigon.social URLs. Uploaded
# files persist in the named volume ${CONTENT_VOLUME}, mounted at /data/content.
#
# Usage:
#   ./deploy/deploy-content.sh [user@]host        # deploy to the given host
#   SSH_HOST=root@1.2.3.4 ./deploy/deploy-content.sh
#   LOCAL=true ./deploy/deploy-content.sh         # build & run on THIS machine (app+Traefik co-located)
#
set -euo pipefail

# ---- Configuration ------------------------------------------------------------------------------
REGISTRY="${REGISTRY:-ghcr.io/saigonsocial}"
IMAGE_NAME="${IMAGE_NAME:-saigon-content}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
IMAGE="${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"

DOMAIN="${DOMAIN:-content.saigon.social}"
CONTAINER_NAME="${CONTAINER_NAME:-saigon-content}"
DOCKER_NETWORK="${DOCKER_NETWORK:-web}"   # shared network created by ./deploy/init-server.sh
CERT_RESOLVER="${CERT_RESOLVER:-letsencrypt}"   # must match ./deploy/init-server.sh
APP_PORT="${APP_PORT:-9091}"              # container port the Ktor content server listens on
CONTENT_VOLUME="${CONTENT_VOLUME:-content-data}"  # named volume the uploaded files persist in

# Where to deploy. By default we ship the image to the remote host over SSH and run it there.
# Set LOCAL=true to build and run on this machine instead (app + Traefik on the same box).
SSH_HOST="${1:-${SSH_HOST:-root@${DOMAIN}}}"
SSH_OPTS="${SSH_OPTS:--o StrictHostKeyChecking=accept-new}"
LOCAL="${LOCAL:-false}"

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
# Build the self-contained content-server JAR with Gradle, then stage it next to the Dockerfile
# (outside any build/ dir so .dockerignore doesn't strip it) to use as a tiny Docker build context.
JAR_SRC="content/build/libs/content-all.jar"
DIST_STAGE="deploy/content/dist"

echo ">> Building content-server fat JAR with Gradle (:content:buildFatJar)"
chmod +x ./gradlew
./gradlew --no-daemon :content:buildFatJar

if [ ! -f "${JAR_SRC}" ]; then
  echo "ERROR: expected build output not found at ${JAR_SRC}" >&2
  exit 1
fi

echo ">> Staging content-server JAR into ${DIST_STAGE}"
rm -rf "${DIST_STAGE}"
mkdir -p "${DIST_STAGE}"
cp "${JAR_SRC}" "${DIST_STAGE}/content.jar"

# ---- Build --------------------------------------------------------------------------------------
echo ">> Building content-server image ${IMAGE}"
${DOCKER} build \
  -f deploy/content/Dockerfile \
  -t "${IMAGE}" \
  deploy/content

# ---- Render the `docker run` command shared by local & remote -----------------------------------
# NOTE: we deliberately do NOT publish host ports — Traefik reaches the container over the shared
# `web` Docker network. Publishing 9091 is unnecessary and risks a port clash on the host.
run_container() {
  # $1 = docker command to use (e.g. "docker" locally, or "docker" on the remote shell)
  local D="$1"
  ${D} rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
  ${D} volume inspect "${CONTENT_VOLUME}" >/dev/null 2>&1 || ${D} volume create "${CONTENT_VOLUME}"
  ${D} run -d \
    --name "${CONTAINER_NAME}" \
    --restart unless-stopped \
    --network "${DOCKER_NETWORK}" \
    -e "PORT=${APP_PORT}" \
    -e "HOST=0.0.0.0" \
    -v "${CONTENT_VOLUME}:/data/content" \
    --label "traefik.enable=true" \
    --label "traefik.http.routers.saigon-content.rule=Host(\`${DOMAIN}\`)" \
    --label "traefik.http.routers.saigon-content.entrypoints=websecure" \
    --label "traefik.http.routers.saigon-content.tls.certresolver=${CERT_RESOLVER}" \
    --label "traefik.http.services.saigon-content.loadbalancer.server.port=${APP_PORT}" \
    "${IMAGE}"
}

if [ "${LOCAL}" = "true" ]; then
  # ---- Run locally ------------------------------------------------------------------------------
  echo ">> (Re)starting container ${CONTAINER_NAME} locally for ${DOMAIN}"
  run_container "${DOCKER}"
  echo ">> Content server deployed locally: https://${DOMAIN} (container '${CONTAINER_NAME}')"
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
  CONTENT_VOLUME="${CONTENT_VOLUME}" \
  'bash -se' <<'REMOTE'
set -euo pipefail

# Make sure the shared network exists (init-server.sh creates it; be defensive on re-runs).
docker network inspect "${DOCKER_NETWORK}" >/dev/null 2>&1 || docker network create "${DOCKER_NETWORK}"

# Uploaded files persist across deploys in a named volume.
docker volume inspect "${CONTENT_VOLUME}" >/dev/null 2>&1 || docker volume create "${CONTENT_VOLUME}"

docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
docker run -d \
  --name "${CONTAINER_NAME}" \
  --restart unless-stopped \
  --network "${DOCKER_NETWORK}" \
  -e "PORT=${APP_PORT}" \
  -e "HOST=0.0.0.0" \
  -v "${CONTENT_VOLUME}:/data/content" \
  --label "traefik.enable=true" \
  --label "traefik.http.routers.saigon-content.rule=Host(\`${DOMAIN}\`)" \
  --label "traefik.http.routers.saigon-content.entrypoints=websecure" \
  --label "traefik.http.routers.saigon-content.tls.certresolver=${CERT_RESOLVER}" \
  --label "traefik.http.services.saigon-content.loadbalancer.server.port=${APP_PORT}" \
  "${IMAGE}"
REMOTE

echo ">> Content server deployed: https://${DOMAIN} (container '${CONTAINER_NAME}' on ${SSH_HOST})"
echo ">> Verify TLS in ~30s with: ./deploy/check-tls.sh ${DOMAIN}"
