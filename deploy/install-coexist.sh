#!/usr/bin/env bash
set -euo pipefail

# Install the relay on a host that ALREADY serves a website.
#
#   sudo bash deploy/install-coexist.sh
#
# The difference from deploy/install.sh is entirely about what this script
# refuses to touch. It never:
#
#   * edits or removes an existing nginx site (including sites-enabled/default)
#   * edits /etc/redis/redis.conf or restarts the box's existing redis-server
#   * runs certbot against the apex domain, which would rewrite the existing
#     site's server block
#   * reloads nginx without `nginx -t` passing first
#
# Everything it installs is namespaced: its own subdomain, its own Redis
# instance on 6380, its own systemd units.
#
# Idempotent. Re-run after a `git pull` to redeploy the app code.

DOMAIN="relay.hatamidev.com"
TURN_DOMAIN="turn.hatamidev.com"
APP_USER="relay"
APP_DIR="/opt/relay"
SERVER_DIR="${APP_DIR}/server"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EMAIL="${CERTBOT_EMAIL:-admin@hatamidev.com}"

step() { echo; echo "==> $*"; }
warn() { echo "  !! $*"; }

if [[ "${EUID}" -ne 0 ]]; then
  echo "Refusing to run: must be root. Try: sudo bash deploy/install-coexist.sh" >&2
  exit 1
fi

# ── 0. Snapshot the existing web config ──────────────────────────────────────
# If anything below goes wrong, this is what you restore from. Taken before
# the first change, not after.
BACKUP="/root/relay-preinstall-backup-$(date +%Y%m%d-%H%M%S).tar.gz"
step "Backing up current nginx + redis config to ${BACKUP}"
tar czf "${BACKUP}" /etc/nginx /etc/redis 2>/dev/null || true
echo "  restore with: tar xzf ${BACKUP} -C /"

# ── 1. Packages ──────────────────────────────────────────────────────────────
step "Installing packages"
apt-get update -y
apt-get install -y curl ca-certificates rsync redis-server coturn \
  certbot python3-certbot-nginx

if ! command -v nginx >/dev/null 2>&1; then
  echo "nginx is not installed. This script assumes the box already serves a" >&2
  echo "site through nginx. Install nginx first, or use deploy/install.sh." >&2
  exit 1
fi

if ! command -v node >/dev/null 2>&1 || [[ "$(node -v | sed 's/^v//' | cut -d. -f1)" -lt 20 ]]; then
  step "Installing Node.js 20.x"
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
else
  step "Node.js already present: $(node -v)"
fi

# The distro package starts a default instance on 6379. That may be the one
# the website uses, so it is left running and untouched — we only need the
# redis-server *binary*.
step "Leaving any existing redis-server on :6379 exactly as it is"

# ── 2. App user and code ─────────────────────────────────────────────────────
step "Creating system user '${APP_USER}'"
if ! id -u "${APP_USER}" >/dev/null 2>&1; then
  useradd --system --home-dir "${SERVER_DIR}" --create-home --shell /usr/sbin/nologin "${APP_USER}"
else
  echo "  already exists"
fi

step "Syncing application code to ${SERVER_DIR}"
mkdir -p "${APP_DIR}"
rsync -a --delete --exclude node_modules --exclude .git --exclude data \
  --exclude secrets --exclude .env "${REPO_ROOT}/server/" "${SERVER_DIR}/"
mkdir -p "${SERVER_DIR}/data" "${SERVER_DIR}/secrets"

if [[ ! -f "${SERVER_DIR}/.env" ]]; then
  step "Seeding ${SERVER_DIR}/.env"
  cp "${SERVER_DIR}/.env.production" "${SERVER_DIR}/.env"
  # This box uses the subdomain and the dedicated Redis port.
  sed -i "s|^PUBLIC_ORIGIN=.*|PUBLIC_ORIGIN=https://${DOMAIN}|" "${SERVER_DIR}/.env"
  sed -i "s|^REDIS_URL=.*|REDIS_URL=redis://:CHANGE_ME_redis_password@127.0.0.1:6380|" "${SERVER_DIR}/.env"
  chmod 600 "${SERVER_DIR}/.env"
  warn "${SERVER_DIR}/.env holds CHANGE_ME placeholders — fill them in (step 2 of the README)."
else
  echo "  .env already present, leaving it untouched"
fi
chown -R "${APP_USER}:${APP_USER}" "${SERVER_DIR}"

step "Installing npm dependencies (production only)"
sudo -u "${APP_USER}" bash -c "cd '${SERVER_DIR}' && npm ci --omit=dev"

# ── 3. Dedicated Redis on 6380 ───────────────────────────────────────────────
step "Installing the relay's own Redis instance (port 6380)"
if [[ -f /etc/redis/relay-redis.conf ]]; then
  echo "  /etc/redis/relay-redis.conf exists — it may hold a live password, leaving it"
else
  install -m 640 -o redis -g redis "${REPO_ROOT}/deploy/redis/relay-redis.conf" \
    /etc/redis/relay-redis.conf
  warn "/etc/redis/relay-redis.conf needs a requirepass value."
fi
install -m 644 "${REPO_ROOT}/deploy/systemd/redis-relay.service" \
  /etc/systemd/system/redis-relay.service
touch /var/log/redis/redis-relay.log 2>/dev/null || true
chown redis:redis /var/log/redis/redis-relay.log 2>/dev/null || true

# ── 4. App unit ──────────────────────────────────────────────────────────────
step "Installing the relay systemd unit"
install -m 644 "${REPO_ROOT}/deploy/systemd/relay-signaling.service" \
  /etc/systemd/system/relay-signaling.service
# The unit as committed orders itself after the default redis-server; on this
# box the relay talks to redis-relay instead.
sed -i 's/redis-server\.service/redis-relay.service/' \
  /etc/systemd/system/relay-signaling.service
systemctl daemon-reload

# ── 5. nginx subdomain ───────────────────────────────────────────────────────
step "Installing the ${DOMAIN} site (additive — existing sites untouched)"
mkdir -p /var/www/certbot
install -m 644 "${REPO_ROOT}/deploy/nginx/${DOMAIN}.conf" \
  "/etc/nginx/sites-available/${DOMAIN}.conf"

# The TLS block references a certificate that does not exist yet, and nginx
# refuses to start with a missing cert. So enable the HTTP half first, get
# the certificate, then enable the whole file.
CERT_DIR="/etc/letsencrypt/live/${DOMAIN}"
if [[ ! -d "${CERT_DIR}" ]]; then
  step "Requesting a certificate for ${DOMAIN} and ${TURN_DOMAIN}"
  echo "  (DNS A records for both must already point here)"

  TMP_SITE="/etc/nginx/sites-available/${DOMAIN}-acme.conf"
  cat > "${TMP_SITE}" <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN} ${TURN_DOMAIN};
    location /.well-known/acme-challenge/ { root /var/www/certbot; }
    location / { return 404; }
}
EOF
  ln -sf "${TMP_SITE}" "/etc/nginx/sites-enabled/${DOMAIN}-acme.conf"
  nginx -t && systemctl reload nginx

  # --webroot, not --nginx: the nginx plugin rewrites server blocks, and the
  # one it would most like to rewrite is the existing site's.
  certbot certonly --webroot -w /var/www/certbot \
    -d "${DOMAIN}" -d "${TURN_DOMAIN}" \
    --non-interactive --agree-tos -m "${EMAIL}"

  rm -f "/etc/nginx/sites-enabled/${DOMAIN}-acme.conf" "${TMP_SITE}"
else
  echo "  certificate already present, skipping certbot"
fi

ln -sf "/etc/nginx/sites-available/${DOMAIN}.conf" \
  "/etc/nginx/sites-enabled/${DOMAIN}.conf"

if ! nginx -t; then
  # Back the change out rather than leaving a config that will fail to load on
  # the next restart and take the website down with it.
  rm -f "/etc/nginx/sites-enabled/${DOMAIN}.conf"
  echo "nginx config test FAILED — the relay site was removed and your existing" >&2
  echo "site is untouched. Fix the error above and re-run." >&2
  exit 1
fi
systemctl reload nginx

# ── 6. coturn ────────────────────────────────────────────────────────────────
step "Installing coturn config"
if [[ -f /etc/turnserver.conf ]] && grep -q "turn.hatamidev.com" /etc/turnserver.conf 2>/dev/null; then
  echo "  already configured for this relay, leaving it (may hold a live secret)"
else
  [[ -f /etc/turnserver.conf ]] && cp /etc/turnserver.conf /etc/turnserver.conf.orig
  install -m 640 "${REPO_ROOT}/deploy/coturn/turnserver.conf" /etc/turnserver.conf
  # coturn shares the certificate nginx just obtained.
  sed -i "s|/etc/letsencrypt/live/hatamidev.com/|/etc/letsencrypt/live/${DOMAIN}/|g" \
    /etc/turnserver.conf
  chgrp turnserver /etc/turnserver.conf 2>/dev/null || true
  sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn 2>/dev/null || true
  warn "/etc/turnserver.conf needs static-auth-secret set."
fi

# coturn runs as its own user and cannot read Let's Encrypt's private keys by
# default. Granting the group rather than loosening the file mode keeps the
# key unreadable to everyone else on the box.
step "Granting coturn read access to the certificate"
groupadd -f ssl-cert
usermod -aG ssl-cert turnserver 2>/dev/null || true
chgrp -R ssl-cert /etc/letsencrypt/live /etc/letsencrypt/archive 2>/dev/null || true
chmod -R g+rX /etc/letsencrypt/live /etc/letsencrypt/archive 2>/dev/null || true

# Certbot renews on its own timer; without this hook coturn keeps serving the
# old certificate until someone notices, weeks later, that TURNS is failing.
cat > /etc/letsencrypt/renewal-hooks/deploy/10-relay-reload.sh <<'EOF'
#!/usr/bin/env bash
# Re-apply group read on the freshly written cert, then pick it up.
chgrp -R ssl-cert /etc/letsencrypt/live /etc/letsencrypt/archive || true
chmod -R g+rX /etc/letsencrypt/live /etc/letsencrypt/archive || true
systemctl reload nginx || true
systemctl restart coturn || true
EOF
mkdir -p /etc/letsencrypt/renewal-hooks/deploy
chmod 755 /etc/letsencrypt/renewal-hooks/deploy/10-relay-reload.sh

# ── 7. Start ─────────────────────────────────────────────────────────────────
step "Enabling services"
systemctl enable redis-relay coturn relay-signaling >/dev/null 2>&1 || true

cat <<EOF

──────────────────────────────────────────────────────────────────────────────
Installed. Your existing site was not modified; a backup of /etc/nginx and
/etc/redis is at ${BACKUP}.

The services are enabled but NOT started, because three secrets are still
placeholders and starting now would just crash-loop. Fill them in:

  1. openssl rand -hex 48   -> JWT_SECRET              in ${SERVER_DIR}/.env
     openssl rand -hex 32   -> BOOTSTRAP_SECRET        in ${SERVER_DIR}/.env
     openssl rand -hex 32   -> TURN_STATIC_AUTH_SECRET in ${SERVER_DIR}/.env
                               AND static-auth-secret  in /etc/turnserver.conf
     openssl rand -hex 32   -> requirepass             in /etc/redis/relay-redis.conf
                               AND the password in REDIS_URL in ${SERVER_DIR}/.env

  2. systemctl start redis-relay coturn relay-signaling

  3. curl -s https://${DOMAIN}/health

Keep BOOTSTRAP_SECRET — the Android build needs the same value.
──────────────────────────────────────────────────────────────────────────────
EOF
