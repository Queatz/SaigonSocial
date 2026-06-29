#!/usr/bin/env bash
#
# Truncate (empty) the Saigon Social ArangoDB database on its host.
#
#   Host : api.saigon.social  (the box that runs the API + ArangoDB containers)
#
# WHAT THIS DOES:
#   Removes *all documents* from every non-system collection in the target database, leaving the
#   collections, indexes, and the database itself intact. This wipes the application's data (users,
#   rooms, etc.) without destroying schema, so the server can keep running and repopulate. The work
#   is done with `arangosh` executed INSIDE the ArangoDB container (`docker exec`), so no port needs
#   to be published on the host.
#
#   By default we connect to the remote host over SSH and run the truncation there. Set LOCAL=true to
#   truncate the ArangoDB container running on THIS machine instead.
#
# WARNING:
#   This is destructive and irreversible. You will be asked to confirm unless FORCE=true is set.
#
# Usage:
#   ./deploy/truncate-db.sh [user@]host           # truncate the DB on the given host (prompts)
#   SSH_HOST=root@1.2.3.4 ./deploy/truncate-db.sh
#   FORCE=true ./deploy/truncate-db.sh            # skip the confirmation prompt
#   LOCAL=true ./deploy/truncate-db.sh            # truncate the ArangoDB on THIS machine
#
set -euo pipefail

# ---- Configuration ------------------------------------------------------------------------------
DOMAIN="${DOMAIN:-api.saigon.social}"

# ArangoDB — these defaults MUST match ./deploy/deploy-server.sh and ./deploy/init-server.sh.
ARANGO_CONTAINER="${ARANGO_CONTAINER:-saigon-arango}"
ARANGO_PORT="${ARANGO_PORT:-8529}"
ARANGO_USER="${ARANGO_USER:-root}"
ARANGO_PASSWORD="${ARANGO_PASSWORD:-saigon}"
ARANGO_DB="${ARANGO_DB:-saigon_social}"

# Where to operate. By default we SSH to the remote host; set LOCAL=true to act on this machine.
SSH_HOST="${1:-${SSH_HOST:-root@${DOMAIN}}}"
SSH_OPTS="${SSH_OPTS:--o StrictHostKeyChecking=accept-new}"
LOCAL="${LOCAL:-false}"

# Skip the interactive confirmation when FORCE=true.
FORCE="${FORCE:-false}"

# ---- Confirmation -------------------------------------------------------------------------------
if [ "${LOCAL}" = "true" ]; then
  TARGET_DESC="the LOCAL machine"
else
  TARGET_DESC="${SSH_HOST}"
fi

if [ "${FORCE}" != "true" ]; then
  echo "!! About to TRUNCATE all collections in database '${ARANGO_DB}' on ${TARGET_DESC}."
  echo "!! This permanently deletes ALL documents and cannot be undone."
  read -r -p "Type 'yes' to continue: " CONFIRM
  if [ "${CONFIRM}" != "yes" ]; then
    echo ">> Aborted; nothing was changed."
    exit 1
  fi
fi

# ---- Truncation logic shared by local & remote --------------------------------------------------
# Runs inside the ArangoDB container: iterate over every non-system collection in ARANGO_DB and
# truncate it. We address the DB explicitly via --server.database so we never touch _system.
read -r -d '' ARANGO_JS <<'JS' || true
db._collections()
  .map(function (c) { return c.name(); })
  .filter(function (n) { return n.charAt(0) !== '_'; })
  .forEach(function (n) {
    db._collection(n).truncate();
    print('   - truncated ' + n);
  });
JS

# $1 = docker command to use (e.g. "docker" locally, or "docker" on the remote shell)
truncate_db() {
  local D="$1"
  if [ -z "$(${D} ps -q -f "name=^${ARANGO_CONTAINER}$")" ]; then
    echo "ERROR: ArangoDB container '${ARANGO_CONTAINER}' is not running." >&2
    exit 1
  fi
  echo ">> Truncating collections in database '${ARANGO_DB}'"
  ${D} exec -i "${ARANGO_CONTAINER}" arangosh \
    --server.endpoint "tcp://127.0.0.1:${ARANGO_PORT}" \
    --server.database "${ARANGO_DB}" \
    --server.username "${ARANGO_USER}" \
    --server.password "${ARANGO_PASSWORD}" \
    --quiet \
    --javascript.execute-string "${ARANGO_JS}"
}

if [ "${LOCAL}" = "true" ]; then
  # ---- Truncate locally -------------------------------------------------------------------------
  # Pick a working Docker invocation: plain `docker`, or `sudo docker` if needed.
  if [ -n "${DOCKER:-}" ]; then
    :
  elif docker info >/dev/null 2>&1; then
    DOCKER="docker"
  elif command -v sudo >/dev/null 2>&1 && sudo docker info >/dev/null 2>&1; then
    DOCKER="sudo docker"
  else
    echo "ERROR: cannot access the Docker daemon." >&2
    exit 1
  fi
  truncate_db "${DOCKER}"
  echo ">> Database '${ARANGO_DB}' truncated locally."
  exit 0
fi

# ---- Truncate on the remote host over SSH -------------------------------------------------------
echo ">> Truncating database on ${SSH_HOST}"
# shellcheck disable=SC2029  # we intentionally expand the env vars locally into the remote command.
# NOTE: we must NOT pass ARANGO_JS as an `ssh host VAR=value ...` prefix: ssh concatenates all
# arguments into ONE remote command string, so the multi-line JavaScript (with newlines and
# parentheses) would break the remote shell. Instead we transmit the whole remote script — JS
# included — over stdin via the quoted heredoc, and define ARANGO_JS literally inside it.
ssh ${SSH_OPTS} "${SSH_HOST}" \
  ARANGO_CONTAINER="${ARANGO_CONTAINER}" \
  ARANGO_PORT="${ARANGO_PORT}" \
  ARANGO_USER="${ARANGO_USER}" \
  ARANGO_PASSWORD="${ARANGO_PASSWORD}" \
  ARANGO_DB="${ARANGO_DB}" \
  'bash -se' <<'REMOTE'
set -euo pipefail

if [ -z "$(docker ps -q -f "name=^${ARANGO_CONTAINER}$")" ]; then
  echo "ERROR: ArangoDB container '${ARANGO_CONTAINER}' is not running." >&2
  exit 1
fi

# The truncation script, embedded literally so it travels over stdin (not the command line).
read -r -d '' ARANGO_JS <<'JS' || true
db._collections()
  .map(function (c) { return c.name(); })
  .filter(function (n) { return n.charAt(0) !== '_'; })
  .forEach(function (n) {
    db._collection(n).truncate();
    print('   - truncated ' + n);
  });
JS

echo "   - truncating collections in database '${ARANGO_DB}'"
docker exec -i "${ARANGO_CONTAINER}" arangosh \
  --server.endpoint "tcp://127.0.0.1:${ARANGO_PORT}" \
  --server.database "${ARANGO_DB}" \
  --server.username "${ARANGO_USER}" \
  --server.password "${ARANGO_PASSWORD}" \
  --quiet \
  --javascript.execute-string "${ARANGO_JS}"
REMOTE

echo ">> Database '${ARANGO_DB}' truncated on ${SSH_HOST}."
