#!/usr/bin/env bash
set -euo pipefail

# Idempotent installer: relay-signaling on hatamidev.com, Ubuntu 22.04/24.04.
#
#   sudo bash deploy/install.sh
#
# Safe to re-run: every step checks the current state before acting instead
# of assuming a clean box. Config files that carry secrets (coturn, redis)
# are installed only the first time, so a re-run never clobbers a value you
# hand-edited on the host; everything else (systemd unit, nginx site, app
# code, npm deps) is always re-synced from this checkout.

DOMAIN="hatamidev.com"
TURN_DOMAIN="turn.hatamidev.com"
APP_USER="relay"
APP_DIR="/opt/relay"
SERVER_DIR="${APP_DIR}/server"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

step() { echo; echo "==> $*"; }

if [[ "${EUID}" -ne 0 ]]; then
  echo "Refusing to run: must be root. Try: sudo bash deploy/install.sh" >&2
  exit 1
fi

step "Installing OS packages (nginx, redis, coturn, certbot)"
apt-get update -y
apt-get install -y curl gnupg2 ca-certificates lsb-release rsync \
  nginx redis-server coturn certbot python3-certbot-nginx

if ! command -v node >/dev/null 2>&1 || [[ "$(node -v | sed 's/^v//' | cut -d. -f1)" -lt 20 ]]; then
  step "Installing Node.js 20.x"
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
else
  step "Node.js already present: $(node -v)"
fi

step "Creating system user '${APP_USER}'"
if ! id -u "${APP_USER}" >/dev/null 2>&1; then
  useradd --system --home-dir "${SERVER_DIR}" --create-home --shell /usr/sbin/nologin "${APP_USER}"
  echo "created ${APP_USER}"
else
  echo "${APP_USER} already exists, skipping"
fi

step "Deploying application code to ${SERVER_DIR}"
mkdir -p "${APP_DIR}"
rsync -a --delete --exclude node_modules --exclude .git --exclude data --exclude secrets \
  "${REPO_ROOT}/server/" "${SERVER_DIR}/"
mkdir -p "${SERVER_DIR}/data" "${SERVER_DIR}/secrets"

if [[ ! -f "${SERVER_DIR}/.env" ]]; then
  step "No .env present — seeding from .env.production"
  cp "${SERVER_DIR}/.env.production" "${SERVER_DIR}/.env"
  chmod 600 "${SERVER_DIR}/.env"
  echo "  !! ${SERVER_DIR}/.env still holds CHANGE_ME placeholders — edit before starting."
else
  echo ".env already present, leaving it untouched"
fi

chown -R "${APP_USER}:${APP_USER}" "${SERVER_DIR}"

step "Installing npm dependencies (production only)"
sudo -u "${APP_USER}" bash -c "cd '${SERVER_DIR}' && npm ci --omit=dev"

step "Installing systemd unit"
install -m 644 "${REPO_ROOT}/deploy/systemd/relay-signaling.service" \
  /etc/systemd/system/relay-signaling.service
systemctl daemon-reload

step "Installing nginx site"
install -m 644 "${REPO_ROOT}/deploy/nginx/${DOMAIN}.conf" \
  "/etc/nginx/sites-available/${DOMAIN}.conf"
ln -sf "/etc/nginx/sites-available/${DOMAIN}.conf" "/etc/nginx/sites-enabled/${DOMAIN}.conf"
rm -f /etc/nginx/sites-enabled/default
mkdir -p /var/www/certbot
nginx -t

step "Installing coturn config"
if [[ -f /etc/turnserver.conf ]]; then
  echo "/etc/turnserver.conf already exists, leaving it untouched (it may hold a live secret)"
else
  install -m 640 "${REPO_ROOT}/deploy/coturn/turnserver.conf" /etc/turnserver.conf
  chgrp turnserver /etc/turnserver.conf 2>/dev/null || true
  sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn 2>/dev/null || true
  echo "  !! /etc/turnserver.conf still holds a CHANGE_ME placeholder — set static-auth-secret" \
       "to match TURN_STATIC_AUTH_SECRET in ${SERVER_DIR}/.env before starting coturn."
fi

step "Installing redis config"
if [[ -f /etc/redis/redis.conf.orig ]] || grep -q "relay-signaling's room store" /etc/redis/redis.conf 2>/dev/null; then
  echo "redis already configured for the relay, leaving /etc/redis/redis.conf untouched"
else
  cp /etc/redis/redis.conf /etc/redis/redis.conf.orig 2>/dev/null || true
  install -m 640 -o redis -g redis "${REPO_ROOT}/deploy/redis/redis.conf" /etc/redis/redis.conf
  echo "  !! /etc/redis/redis.conf still holds a CHANGE_ME placeholder — set requirepass and" \
       "mirror it into REDIS_URL in ${SERVER_DIR}/.env before starting redis-server."
fi

step "Starting nginx over HTTP so certbot's HTTP-01 challenge can pass"
systemctl enable --now nginx
systemctl reload nginx

step "Requesting a TLS certificate for ${DOMAIN}"
if [[ -d "/etc/letsencrypt/live/${DOMAIN}" ]]; then
  echo "certificate for ${DOMAIN} already present, skipping certbot"
else
  certbot --nginx -d "${DOMAIN}" --non-interactive --agree-tos -m "admin@${DOMAIN}" --redirect
fi

step "Enabling and starting services"
systemctl enable redis-server coturn relay-signaling
systemctl restart redis-server coturn relay-signaling
systemctl reload nginx

step "Install complete."
cat <<EOF

Before this is actually reachable and secure, finish the manual steps the
notes above flagged with "!!":
  1. Edit ${SERVER_DIR}/.env    — JWT_SECRET, BOOTSTRAP_SECRET,
                                   TURN_STATIC_AUTH_SECRET, REDIS_URL password.
  2. Edit /etc/turnserver.conf  — static-auth-secret must match .env.
  3. Edit /etc/redis/redis.conf — requirepass must match .env's REDIS_URL.
  4. systemctl restart redis-server coturn relay-signaling

DNS records this whole setup assumes (create before running certbot again
on a fresh box — see deploy/README.md for the full runbook):
  A  ${DOMAIN}       -> this host's public IP
  A  ${TURN_DOMAIN}  -> this host's public IP

Verify once secrets are filled in:
  curl https://${DOMAIN}/health
EOF
