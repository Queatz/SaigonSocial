#!/usr/bin/env bash
#
# Initialise the Saigon Social *content* host over SSH.
#
#   Target host : content.saigon.social  (the box that will run the content-server container)
#
# This prepares a fresh server so that ./deploy/deploy-content.sh can run against it:
#   1. installs Docker (engine + compose plugin) if missing,
#   2. creates the shared `web` Docker network used for host-based routing,
#   3. brings up a Traefik reverse proxy that terminates TLS via Let's Encrypt,
#   4. ensures the named volume the uploaded files persist in exists.
#
# It is idempotent: re-running it only applies what is missing. If the content server shares a
# single host with the API and/or web client, you only need to run one of the init scripts.
#
# Usage:
#   ./deploy/init-content.sh [user@]host
#   SSH_HOST=root@1.2.3.4 ./deploy/init-content.sh
#
# Override any of the variables below from the environment, e.g.:
#   ACME_EMAIL=ops@saigon.social ./deploy/init-content.sh deploy@content.saigon.social
#
set -euo pipefail

# ---- Configuration ------------------------------------------------------------------------------
SSH_HOST="${1:-${SSH_HOST:-root@content.saigon.social}}"
SSH_OPTS="${SSH_OPTS:--o StrictHostKeyChecking=accept-new}"

DOCKER_NETWORK="${DOCKER_NETWORK:-web}"          # shared network Traefik + app containers join
TRAEFIK_CONTAINER="${TRAEFIK_CONTAINER:-traefik}"
CERT_RESOLVER="${CERT_RESOLVER:-letsencrypt}"    # must match the deploy scripts' certresolver label
ACME_EMAIL="${ACME_EMAIL:-admin@saigon.social}"  # Let's Encrypt registration / expiry notices

# The named volume uploaded files persist in. MUST match the CONTENT_VOLUME used by
# ./deploy/deploy-content.sh (mounted at /data/content in the container).
CONTENT_VOLUME="${CONTENT_VOLUME:-content-data}"

# ---- Remote bootstrap ---------------------------------------------------------------------------
# Everything below runs on the remote host. We pass our config through as environment variables.
echo ">> Initialising content host ${SSH_HOST}"

# shellcheck disable=SC2029  # we intentionally expand the env vars locally into the remote command.
ssh ${SSH_OPTS} "${SSH_HOST}" \
  DOCKER_NETWORK="${DOCKER_NETWORK}" \
  TRAEFIK_CONTAINER="${TRAEFIK_CONTAINER}" \
  CERT_RESOLVER="${CERT_RESOLVER}" \
  ACME_EMAIL="${ACME_EMAIL}" \
  CONTENT_VOLUME="${CONTENT_VOLUME}" \
  'bash -se' <<'REMOTE'
set -euo pipefail

echo "   - checking Docker"
if ! command -v docker >/dev/null 2>&1; then
  echo "   - installing Docker"
  curl -fsSL https://get.docker.com | sh
  systemctl enable --now docker || true
else
  echo "   - Docker already installed: $(docker --version)"
fi

echo "   - ensuring Docker network '${DOCKER_NETWORK}'"
docker network inspect "${DOCKER_NETWORK}" >/dev/null 2>&1 \
  || docker network create "${DOCKER_NETWORK}"

echo "   - ensuring Traefik reverse proxy '${TRAEFIK_CONTAINER}'"
if [ -z "$(docker ps -q -f "name=^${TRAEFIK_CONTAINER}$")" ]; then
  docker rm -f "${TRAEFIK_CONTAINER}" >/dev/null 2>&1 || true
  mkdir -p /opt/traefik
  touch /opt/traefik/acme.json
  chmod 600 /opt/traefik/acme.json
  docker run -d \
    --name "${TRAEFIK_CONTAINER}" \
    --restart unless-stopped \
    --network "${DOCKER_NETWORK}" \
    -p 80:80 \
    -p 443:443 \
    -v /var/run/docker.sock:/var/run/docker.sock:ro \
    -v /opt/traefik/acme.json:/acme.json \
    traefik:v3 \
    --providers.docker=true \
    --providers.docker.exposedbydefault=false \
    --entrypoints.web.address=:80 \
    --entrypoints.websecure.address=:443 \
    --entrypoints.web.http.redirections.entrypoint.to=websecure \
    --entrypoints.web.http.redirections.entrypoint.scheme=https \
    "--certificatesresolvers.${CERT_RESOLVER}.acme.email=${ACME_EMAIL}" \
    "--certificatesresolvers.${CERT_RESOLVER}.acme.storage=/acme.json" \
    "--certificatesresolvers.${CERT_RESOLVER}.acme.httpchallenge.entrypoint=web"
else
  echo "   - Traefik already running"
fi

echo "   - ensuring content volume '${CONTENT_VOLUME}'"
docker volume inspect "${CONTENT_VOLUME}" >/dev/null 2>&1 \
  || docker volume create "${CONTENT_VOLUME}"

echo ">> Content host ready."
REMOTE

echo ">> Done. You can now run: ./deploy/deploy-content.sh"
