#!/usr/bin/env bash
#
# Initialise the Saigon Social *backend* host over SSH.
#
#   Target host : api.saigon.social  (the box that will run the API container)
#
# This prepares a fresh server so that ./deploy/deploy-server.sh can run against it:
#   1. installs Docker (engine + compose plugin) if missing,
#   2. creates the shared `web` Docker network used for host-based routing,
#   3. brings up a Traefik reverse proxy that terminates TLS via Let's Encrypt.
#
# It is idempotent: re-running it only applies what is missing.
#
# Usage:
#   ./deploy/init-server.sh [user@]host
#   SSH_HOST=root@1.2.3.4 ./deploy/init-server.sh
#
# Override any of the variables below from the environment, e.g.:
#   ACME_EMAIL=ops@saigon.social ./deploy/init-server.sh deploy@api.saigon.social
#
set -euo pipefail

# ---- Configuration ------------------------------------------------------------------------------
SSH_HOST="${1:-${SSH_HOST:-root@api.saigon.social}}"
SSH_OPTS="${SSH_OPTS:--o StrictHostKeyChecking=accept-new}"

DOCKER_NETWORK="${DOCKER_NETWORK:-web}"          # shared network Traefik + app containers join
TRAEFIK_CONTAINER="${TRAEFIK_CONTAINER:-traefik}"
CERT_RESOLVER="${CERT_RESOLVER:-letsencrypt}"    # must match the deploy scripts' certresolver label
ACME_EMAIL="${ACME_EMAIL:-admin@saigon.social}"  # Let's Encrypt registration / expiry notices

# ---- Remote bootstrap ---------------------------------------------------------------------------
# Everything below runs on the remote host. We pass our config through as environment variables.
echo ">> Initialising backend host ${SSH_HOST}"

# shellcheck disable=SC2029  # we intentionally expand the env vars locally into the remote command.
ssh ${SSH_OPTS} "${SSH_HOST}" \
  DOCKER_NETWORK="${DOCKER_NETWORK}" \
  TRAEFIK_CONTAINER="${TRAEFIK_CONTAINER}" \
  CERT_RESOLVER="${CERT_RESOLVER}" \
  ACME_EMAIL="${ACME_EMAIL}" \
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

echo ">> Backend host ready."
REMOTE

echo ">> Done. You can now run: ./deploy/deploy-server.sh"
