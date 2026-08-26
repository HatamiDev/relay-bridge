# Deploying the relay to hatamidev.com

A fresh Ubuntu 22.04/24.04 box to a working relay in about 10 minutes of
hands-on time (package installs and `certbot` run in the background).

> ### Does this host already serve a website?
>
> **Then do not use this runbook.** It assumes the box is dedicated to the
> relay: `install.sh` takes over the apex domain, overwrites
> `/etc/redis/redis.conf` (which adds a password every existing Redis client
> will immediately fail on) and deletes `sites-enabled/default`.
>
> Use **[`install-coexist.sh`](install-coexist.sh)** instead — same stack, but
> on the `relay.hatamidev.com` subdomain with its own Redis on port 6380, and
> it refuses to modify anything that was already there. Step-by-step guide:
> [`README-fa.md`](README-fa.md) (Persian).

## 0. Before you start

You need, on the machine you'll SSH from:

- root (or sudo) SSH access to a fresh Ubuntu 22.04/24.04 VPS
- This repository checked out on that VPS (`git clone` it, or `rsync` it up)
- A Firebase service-account JSON if you want FCM wake pushes (optional —
  the server runs without it, it just can't wake a sleeping receiver)

### DNS records

Create these **before** step 3 (certbot needs `hatamidev.com` to resolve to
this host to complete the HTTP-01 challenge):

| Type | Name              | Value                  |
|------|-------------------|------------------------|
| A    | `hatamidev.com`   | this host's public IPv4 |
| A    | `turn.hatamidev.com` | this host's public IPv4 |

(Add AAAA records too if the host has a public IPv6 address — nginx and
coturn in this repo both listen on `::` as well as `0.0.0.0`.)

### Firewall

Open these before starting services, otherwise pairing/calls will silently
fail to connect even though the app itself is healthy:

| Port | Proto | Purpose |
|---|---|---|
| 22 | TCP | SSH |
| 80 | TCP | HTTP → HTTPS redirect + certbot HTTP-01 |
| 443 | TCP | HTTPS + WebSocket (Socket.IO) |
| 3478 | UDP + TCP | TURN (plain) |
| 5349 | TCP | TURN over TLS |
| 49152–65535 | UDP | TURN relay candidates (`min-port`/`max-port` in `coturn/turnserver.conf`) |

`ufw` example:

```bash
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 3478
ufw allow 5349/tcp
ufw allow 49152:65535/udp
ufw enable
```

Port 6379 (Redis) is **not** in this list on purpose — `deploy/redis/redis.conf`
binds it to loopback only, and nothing outside this host should ever reach it.

## 1. Run the installer

```bash
cd /path/to/checkout
sudo bash deploy/install.sh
```

This installs Node 20, nginx, redis-server, coturn, and certbot; creates the
`relay` system user; syncs `server/` to `/opt/relay/server`; runs `npm ci`;
installs the systemd unit and nginx site; requests the TLS certificate; and
starts everything. It is idempotent — re-running it after a `git pull` just
re-syncs the app code and restarts the service, it will not overwrite
secrets you've already set in `/etc/turnserver.conf` or `/etc/redis/redis.conf`.

It will stop and print exactly what's left to do — filling in three secrets
— because those can't be safely auto-generated into a file the installer
also wants to be re-runnable.

## 2. Fill in the secrets

```bash
# Three independent secrets. Generate each with:
openssl rand -hex 48   # JWT_SECRET
openssl rand -hex 32   # BOOTSTRAP_SECRET
openssl rand -hex 32   # TURN_STATIC_AUTH_SECRET (must match coturn, see below)
```

Edit `/opt/relay/server/.env`:

```
JWT_SECRET=<paste>
BOOTSTRAP_SECRET=<paste>
TURN_STATIC_AUTH_SECRET=<paste, same value as turnserver.conf below>
REDIS_URL=redis://:<redis password>@127.0.0.1:6379
```

Edit `/etc/turnserver.conf`:

```
static-auth-secret=<same TURN_STATIC_AUTH_SECRET as above>
```

Edit `/etc/redis/redis.conf`:

```
requirepass <a third random value — openssl rand -hex 32>
```

...and put that same password into `REDIS_URL` above.

## 3. Restart everything

```bash
sudo systemctl restart redis-server coturn relay-signaling
sudo systemctl status relay-signaling --no-pager
```

## 4. Verify

```bash
curl -s https://hatamidev.com/health | python3 -m json.tool
```

Expect `{"ok": true, ...}` with `turn: true` and (if you configured Firebase)
`fcm: true`. Then from a device, run a full pair: `POST /pair/create` with
the `x-bootstrap-secret` header set to your `BOOTSTRAP_SECRET`.

```bash
# Confirm the WebSocket upgrade path works end-to-end (should print an
# HTTP/1.1 101 or, over TLS from curl, at least not a 4xx):
curl -i -N \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  https://hatamidev.com/socket.io/?EIO=4&transport=websocket
```

## Logs

```bash
journalctl -u relay-signaling -f      # app
journalctl -u coturn -f               # TURN
tail -f /var/log/redis/redis-server.log
tail -f /var/log/nginx/error.log
```

## Updating a deployed instance

```bash
cd /path/to/checkout && git pull
sudo bash deploy/install.sh           # re-syncs code + npm deps, restarts the unit
```

## Renewing the certificate

Certbot installs its own systemd timer (`certbot.timer`) that renews
automatically and reloads nginx; nothing to do here unless
`systemctl status certbot.timer` shows it's not enabled, in which case:

```bash
sudo systemctl enable --now certbot.timer
```

## File map

| File | Installs to |
|---|---|
| `nginx/hatamidev.com.conf` | `/etc/nginx/sites-available/hatamidev.com.conf` |
| `systemd/relay-signaling.service` | `/etc/systemd/system/relay-signaling.service` |
| `coturn/turnserver.conf` | `/etc/turnserver.conf` |
| `redis/redis.conf` | `/etc/redis/redis.conf` |
| `server/.env.production` | seeds `/opt/relay/server/.env` on first install |
