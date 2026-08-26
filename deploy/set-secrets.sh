#!/usr/bin/env bash
set -euo pipefail

# Generate the four secrets and write them into all three files that must
# agree, then start the services.
#
#   sudo bash deploy/set-secrets.sh
#
# Doing this by hand is where deployments go wrong: TURN_STATIC_AUTH_SECRET
# has to be byte-identical in .env and /etc/turnserver.conf, and the Redis
# password has to appear both in relay-redis.conf and inside the REDIS_URL.
# A single mismatched character produces "TURN credentials rejected" or
# "NOAUTH" at call time, minutes after everything looked like it started fine.
#
# Refuses to overwrite secrets that are already set, so it is safe to re-run
# after a partial install.

SERVER_DIR="/opt/relay/server"
ENV_FILE="${SERVER_DIR}/.env"
TURN_FILE="/etc/turnserver.conf"
REDIS_FILE="/etc/redis/relay-redis.conf"

[[ "${EUID}" -ne 0 ]] && { echo "Must be root: sudo bash deploy/set-secrets.sh" >&2; exit 1; }
for f in "${ENV_FILE}" "${TURN_FILE}" "${REDIS_FILE}"; do
  [[ -f "$f" ]] || { echo "Missing ${f} — run deploy/install-coexist.sh first." >&2; exit 1; }
done

# Replace `key=value` in a shell-style env file, whatever the old value was.
set_env() {
  local key="$1" val="$2"
  if grep -q "^${key}=" "${ENV_FILE}"; then
    # `|` as the delimiter: these values are hex, but REDIS_URL contains
    # slashes and would break a `/`-delimited expression.
    sed -i "s|^${key}=.*|${key}=${val}|" "${ENV_FILE}"
  else
    echo "${key}=${val}" >> "${ENV_FILE}"
  fi
}

current_env() { grep "^$1=" "${ENV_FILE}" | head -1 | cut -d= -f2-; }
is_placeholder() { [[ -z "$1" || "$1" == CHANGE_ME* || "$1" == *CHANGE_ME* ]]; }

echo "==> Generating secrets"

JWT="$(current_env JWT_SECRET)"
if is_placeholder "${JWT}"; then
  JWT="$(openssl rand -hex 48)"; set_env JWT_SECRET "${JWT}"
  echo "  JWT_SECRET: generated"
else
  echo "  JWT_SECRET: already set, keeping"
fi

BOOT="$(current_env BOOTSTRAP_SECRET)"
if is_placeholder "${BOOT}"; then
  BOOT="$(openssl rand -hex 32)"; set_env BOOTSTRAP_SECRET "${BOOT}"
  echo "  BOOTSTRAP_SECRET: generated"
else
  echo "  BOOTSTRAP_SECRET: already set, keeping"
fi

TURN="$(current_env TURN_STATIC_AUTH_SECRET)"
if is_placeholder "${TURN}"; then
  TURN="$(openssl rand -hex 32)"; set_env TURN_STATIC_AUTH_SECRET "${TURN}"
  echo "  TURN_STATIC_AUTH_SECRET: generated"
else
  echo "  TURN_STATIC_AUTH_SECRET: already set, keeping"
fi

REDIS_PASS="$(grep -E '^requirepass ' "${REDIS_FILE}" | awk '{print $2}')"
if is_placeholder "${REDIS_PASS}"; then
  REDIS_PASS="$(openssl rand -hex 32)"
  echo "  redis password: generated"
else
  echo "  redis password: already set, keeping"
fi

echo "==> Writing them where they have to match"

# coturn: the secret the server signs TURN credentials with.
sed -i "s|^static-auth-secret=.*|static-auth-secret=${TURN}|" "${TURN_FILE}"

# Redis: the password, in the config and inside the URL the app dials.
sed -i "s|^requirepass .*|requirepass ${REDIS_PASS}|" "${REDIS_FILE}"
set_env REDIS_URL "redis://:${REDIS_PASS}@127.0.0.1:6380"

chmod 600 "${ENV_FILE}"; chown relay:relay "${ENV_FILE}"
chmod 640 "${TURN_FILE}"; chgrp turnserver "${TURN_FILE}" 2>/dev/null || true
chmod 640 "${REDIS_FILE}"; chown redis:redis "${REDIS_FILE}"

echo "==> Starting services"
systemctl restart redis-relay
sleep 1
systemctl restart coturn relay-signaling
sleep 2

for unit in redis-relay coturn relay-signaling; do
  printf '  %-18s %s\n' "${unit}" "$(systemctl is-active "${unit}")"
done

echo
echo "==> Health"
curl -fsS --max-time 10 https://relay.hatamidev.com/health || echo "  (not answering yet — check: journalctl -u relay-signaling -n 50)"
echo
echo
echo "────────────────────────────────────────────────────────────────────"
echo "BOOTSTRAP_SECRET (put this in the GitHub repo secret"
echo "RELAY_BOOTSTRAP_SECRET, then rebuild the APK):"
echo
echo "    ${BOOT}"
echo "────────────────────────────────────────────────────────────────────"
