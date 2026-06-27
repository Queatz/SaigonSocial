#!/usr/bin/env bash
#
# Verify that HTTPS is working for the Saigon Social hosts.
#
# TLS is handled by Traefik (see ./deploy/init-server.sh / init-web.sh), which auto-provisions
# Let's Encrypt certificates. This script does NOT change anything — it just checks the most common
# reasons certificates fail to issue, so you can confirm HTTPS end-to-end.
#
# Usage:
#   ./deploy/check-tls.sh                       # checks saigon.social and api.saigon.social
#   ./deploy/check-tls.sh example.com a.example  # checks the given hosts instead
#
set -uo pipefail

HOSTS=("$@")
if [ "${#HOSTS[@]}" -eq 0 ]; then
  HOSTS=("saigon.social" "api.saigon.social")
fi

fail=0

for host in "${HOSTS[@]}"; do
  echo "== ${host} =="

  # 1. DNS must resolve (Let's Encrypt HTTP-01 needs the name to point at the host).
  ips="$(getent ahosts "${host}" 2>/dev/null | awk '{print $1}' | sort -u | paste -sd, -)"
  if [ -n "${ips}" ]; then
    echo "   DNS      : ${ips}"
  else
    echo "   DNS      : FAILED to resolve — point an A record at the server's public IP"
    fail=1
  fi

  # 2. Port 80 must be reachable (ACME HTTP-01 challenge + HTTP->HTTPS redirect).
  if curl -sS -o /dev/null --max-time 10 "http://${host}/" 2>/dev/null; then
    echo "   HTTP :80 : reachable"
  else
    echo "   HTTP :80 : NOT reachable — open the firewall and ensure Traefik is up"
    fail=1
  fi

  # 3. Port 443 must serve a valid (non-self-signed) certificate for this host.
  cert="$(echo | timeout 10 openssl s_client -connect "${host}:443" -servername "${host}" 2>/dev/null \
            | openssl x509 -noout -issuer -subject -dates 2>/dev/null)"
  if [ -n "${cert}" ]; then
    echo "   TLS :443 : certificate present"
    echo "${cert}" | sed 's/^/              /'
    if echo "${cert}" | grep -qiE "issuer=.*(Let's Encrypt|R[0-9]+|E[0-9]+)"; then
      echo "              -> issued by Let's Encrypt (Traefik ACME working)"
    elif echo "${cert}" | grep -qi "issuer=CN *= *TRAEFIK DEFAULT CERT"; then
      echo "              -> still Traefik's self-signed default: ACME has not issued yet."
      echo "                 Check 'docker logs traefik' for ACME errors (DNS/rate-limit)."
      fail=1
    fi
  else
    echo "   TLS :443 : no certificate served — Traefik not running or 443 blocked"
    fail=1
  fi
  echo
done

if [ "${fail}" -eq 0 ]; then
  echo ">> HTTPS looks healthy."
else
  echo ">> Some checks failed — see notes above (most issues are DNS or a blocked port 80/443)." >&2
fi
exit "${fail}"
