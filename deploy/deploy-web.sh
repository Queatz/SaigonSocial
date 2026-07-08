#!/usr/bin/env bash
#
# Build, ship, and run the Saigon Social web client on its host.
#
#   Host  : saigon.social
#   Image : ${REGISTRY}/saigon-web:${IMAGE_TAG}
#
# IMPORTANT — how this deploys:
#   The Compose/JS bundle is compiled ON THIS MACHINE with Gradle, packaged into a tiny nginx image
#   locally, then the image is SHIPPED to the remote host over SSH (`docker save | ssh docker load`)
#   and RUN there. (We deploy the JS target rather than Wasm for broad browser support.) The Kotlin
#   web tooling downloads its own Node/yarn which is awkward inside a minimal container, so we build
#   on the host. The app container must run on the same host as Traefik (see ./deploy/init-web.sh)
#   and join the shared `web` Docker network, otherwise Traefik has no router for saigon.social and
#   Let's Encrypt never issues a certificate.
#
#   The backend URL is baked into the bundle at build time (the client reads the compile-time
#   constant SaigonApi.DEFAULT_BASE_URL — it cannot be changed at runtime in the browser), so we
#   inject it into the source before the Gradle build and restore the file afterwards.
#
# Usage:
#   ./deploy/deploy-web.sh [user@]host           # deploy to the given host
#   SSH_HOST=root@1.2.3.4 ./deploy/deploy-web.sh
#   LOCAL=true ./deploy/deploy-web.sh            # build & run on THIS machine (app+Traefik co-located)
#
set -euo pipefail

# ---- Configuration ------------------------------------------------------------------------------
REGISTRY="${REGISTRY:-ghcr.io/saigonsocial}"
IMAGE_NAME="${IMAGE_NAME:-saigon-web}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
IMAGE="${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"

DOMAIN="${DOMAIN:-saigon.social}"
CONTAINER_NAME="${CONTAINER_NAME:-saigon-web}"
DOCKER_NETWORK="${DOCKER_NETWORK:-web}"   # shared network created by ./deploy/init-web.sh
CERT_RESOLVER="${CERT_RESOLVER:-letsencrypt}"   # must match ./deploy/init-web.sh
APP_PORT=80                               # container port nginx listens on

# Where to deploy. By default we ship the image to the remote host over SSH and run it there.
# Set LOCAL=true to build and run on this machine instead (app + Traefik on the same box).
SSH_HOST="${1:-${SSH_HOST:-root@${DOMAIN}}}"
SSH_OPTS="${SSH_OPTS:--o StrictHostKeyChecking=accept-new}"
LOCAL="${LOCAL:-false}"

# Resources baked into the web bundle: where the client talks to the API, and where it uploads /
# loads photos (the content server — see ./deploy/deploy-content.sh).
DEFAULT_BASE_URL="${DEFAULT_BASE_URL:-https://api.saigon.social}"
DEFAULT_CONTENT_URL="${DEFAULT_CONTENT_URL:-https://content.saigon.social}"

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

# ---- Compile the web bundle on the host -----------------------------------------------------------
# Bake the production API URL into the client by editing the compile-time constant, build the
# JS distribution with Gradle, then restore the source file (so the working tree stays clean).
API_FILE="app/shared/src/commonMain/kotlin/social/saigon/ui/Api.kt"
DIST_SRC="app/webApp/build/dist/js/productionExecutable"
DIST_STAGE="deploy/web/dist"

echo ">> Baking DEFAULT_BASE_URL=${DEFAULT_BASE_URL} into ${API_FILE}"
cp "${API_FILE}" "${API_FILE}.deploybak"
restore_api_file() { mv -f "${API_FILE}.deploybak" "${API_FILE}" 2>/dev/null || true; }
trap restore_api_file EXIT
sed -i "s#const val DEFAULT_BASE_URL = \"[^\"]*\"#const val DEFAULT_BASE_URL = \"${DEFAULT_BASE_URL}\"#" \
  "${API_FILE}"
echo ">> Baking DEFAULT_CONTENT_URL=${DEFAULT_CONTENT_URL} into ${API_FILE}"
sed -i "s#const val DEFAULT_CONTENT_URL = \"[^\"]*\"#const val DEFAULT_CONTENT_URL = \"${DEFAULT_CONTENT_URL}\"#" \
  "${API_FILE}"

echo ">> Building web bundle with Gradle (:app:webApp:jsBrowserDistribution)"
chmod +x ./gradlew
./gradlew --no-daemon :app:webApp:jsBrowserDistribution

restore_api_file
trap - EXIT

if [ ! -f "${DIST_SRC}/index.html" ]; then
  echo "ERROR: expected build output not found at ${DIST_SRC}/index.html" >&2
  exit 1
fi

# Stage the freshly built assets next to the Dockerfile (outside any build/ dir so .dockerignore
# doesn't strip them) to use as the Docker build context.
echo ">> Staging web assets into ${DIST_STAGE}"
rm -rf "${DIST_STAGE}"
mkdir -p "${DIST_STAGE}"
cp -R "${DIST_SRC}/." "${DIST_STAGE}/"

# ---- Build --------------------------------------------------------------------------------------
echo ">> Building web image ${IMAGE} (DEFAULT_BASE_URL=${DEFAULT_BASE_URL})"
${DOCKER} build \
  -f deploy/web/Dockerfile \
  -t "${IMAGE}" \
  deploy/web

# ---- Run (local or remote) ----------------------------------------------------------------------
# NOTE: we deliberately do NOT publish host ports — Traefik reaches the container over the shared
# `web` Docker network. Publishing 80 would clash with Traefik, which already binds it.
if [ "${LOCAL}" = "true" ]; then
  echo ">> (Re)starting container ${CONTAINER_NAME} locally for ${DOMAIN}"
  ${DOCKER} rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
  ${DOCKER} run -d \
    --name "${CONTAINER_NAME}" \
    --restart unless-stopped \
    --network "${DOCKER_NETWORK}" \
    --label "traefik.enable=true" \
    --label "traefik.http.routers.saigon-web.rule=Host(\`${DOMAIN}\`)" \
    --label "traefik.http.routers.saigon-web.entrypoints=websecure" \
    --label "traefik.http.routers.saigon-web.tls.certresolver=${CERT_RESOLVER}" \
    --label "traefik.http.services.saigon-web.loadbalancer.server.port=${APP_PORT}" \
    "${IMAGE}"
  echo ">> Web deployed locally: https://${DOMAIN} (container '${CONTAINER_NAME}')"
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
  'bash -se' <<'REMOTE'
set -euo pipefail

# Make sure the shared network exists (init-web.sh creates it; be defensive on re-runs).
docker network inspect "${DOCKER_NETWORK}" >/dev/null 2>&1 || docker network create "${DOCKER_NETWORK}"

docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
docker run -d \
  --name "${CONTAINER_NAME}" \
  --restart unless-stopped \
  --network "${DOCKER_NETWORK}" \
  --label "traefik.enable=true" \
  --label "traefik.http.routers.saigon-web.rule=Host(\`${DOMAIN}\`)" \
  --label "traefik.http.routers.saigon-web.entrypoints=websecure" \
  --label "traefik.http.routers.saigon-web.tls.certresolver=${CERT_RESOLVER}" \
  --label "traefik.http.services.saigon-web.loadbalancer.server.port=${APP_PORT}" \
  "${IMAGE}"
REMOTE

echo ">> Web deployed: https://${DOMAIN} (container '${CONTAINER_NAME}' on ${SSH_HOST})"
echo ">> Verify TLS in ~30s with: ./deploy/check-tls.sh ${DOMAIN}"
