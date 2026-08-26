# Setup — end to end

## 0. What you need

- A small VPS with a public IP and a domain (1 vCPU / 1 GB is plenty)
- Node.js 20+
- coturn (for calls behind NAT — effectively always)
- A Firebase project (for FCM wake pushes)
- Android Studio Ladybug+ / JDK 17
- Galaxy Note 10+ (gateway, SIM installed) and Galaxy S26 Ultra (client)

---

## 1. Signaling server

```bash
cd server
cp .env.example .env
```

Generate real secrets:

```bash
echo "JWT_SECRET=$(openssl rand -hex 48)"
echo "BOOTSTRAP_SECRET=$(openssl rand -hex 32)"
echo "TURN_STATIC_AUTH_SECRET=$(openssl rand -hex 32)"
```

Paste them into `.env`, then set `PUBLIC_ORIGIN=https://relay.example.com` and
your `TURN_URLS`.

```bash
npm install
npm start
# → relay signaling server listening
curl -s https://relay.example.com/health | jq
```

### systemd unit

```ini
[Unit]
Description=Relay signaling server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=relay
WorkingDirectory=/opt/relay/server
ExecStart=/usr/bin/node server.js
Restart=always
RestartSec=5
Environment=NODE_ENV=production
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ReadWritePaths=/opt/relay/server/data
ProtectHome=yes

[Install]
WantedBy=multi-user.target
```

### Caddy reverse proxy (TLS + WebSocket upgrade)

```caddyfile
relay.example.com {
    encode zstd gzip
    reverse_proxy localhost:8443 {
        header_up X-Real-IP {remote_host}
    }
}
```

Caddy passes WebSocket upgrades through by default. With nginx you must add the
`Upgrade`/`Connection` headers explicitly or Socket.IO will fall back to polling.

---

## 2. coturn

```bash
sudo apt install coturn
sudo sed -i 's/#TURNSERVER_ENABLED=1/TURNSERVER_ENABLED=1/' /etc/default/coturn
```

Use the `/etc/turnserver.conf` in `docs/02-SECURITY.md` §3 — including the
`denied-peer-ip` block, which stops your TURN server becoming an SSRF proxy into
your own network.

```bash
sudo systemctl enable --now coturn
```

Verify with the Trickle ICE page or:

```bash
turnutils_uclient -T -u "$(date +%s -d '+1 hour'):test" \
  -w "$(echo -n "$(date +%s -d '+1 hour'):test" | openssl sha1 -hmac "$TURN_SECRET" -binary | base64)" \
  relay.example.com
```

---

## 3. Firebase (FCM)

1. Create a Firebase project.
2. Add **two** Android apps: `com.relay.gateway` and `com.relay.client`.
3. Download each `google-services.json` into `android/gateway/` and
   `android/client/` respectively.
4. Project settings → Service accounts → **Generate new private key**.
5. Put that JSON on the server at `server/secrets/firebase-service-account.json`
   and point `FCM_SERVICE_ACCOUNT_PATH` at it.

FCM is optional. Without it, an offline device only reconnects on its own 30 s
heartbeat — fine for SMS, too slow for a ringing call.

---

## 4. Build the apps

```bash
cd android

./gradlew :gateway:assembleRelease \
  -PrelayServerUrl=https://relay.example.com \
  -PrelayBootstrapSecret=<BOOTSTRAP_SECRET from .env>

./gradlew :client:assembleRelease \
  -PrelayServerUrl=https://relay.example.com
```

The bootstrap secret is compiled into the **gateway only** — it is what
authorises creating a room on your server. The client never needs it.

Install:

```bash
adb -s <NOTE10_SERIAL> install -r gateway/build/outputs/apk/release/gateway-release.apk
adb -s <S26_SERIAL>    install -r client/build/outputs/apk/release/client-release.apk
```

---

## 5. Gateway setup (Note 10+)

Open **Relay Gateway** and walk the five steps in order:

1. **Permissions** — grant everything. SMS and phone-state cannot be granted later without a restart.
2. **Dialer role** — accept. Without `ROLE_DIALER`, Android never binds the `InCallService` and **call relay silently does nothing**. SMS relay still works.
3. **Keep me alive** — grant the Doze exemption, then work through the One UI list in `docs/03-QA-OPTIMIZATION.md` §3.2. Skipping this is the number-one cause of "it stopped working after a day".
4. **Call audio capability** — read what it reports. On a stock retail Note 10+ it will say *speakerphone loopback*. That is expected, not a bug.
5. **Pair** — tap **Generate pairing QR**. Leave the screen on.

---

## 6. Client setup (S26 Ultra)

1. Open **Relay**, grant microphone and notification permissions.
2. Tap **Scan pairing QR** and point it at the gateway's screen.
3. **Compare the six-digit verification code on both phones.** If they differ,
   unpair immediately — someone substituted the QR.
4. Grant the Doze exemption from Settings.

Within a few seconds the client should show *Gateway online* with its battery
level, and the message history should backfill.

---

## 7. Verify

```bash
# From another phone, text the gateway's number.
# → appears on the client within ~2 s

# From the client, reply.
# → SENT then DELIVERED ticks

# From another phone, call the gateway's number.
# → the client rings full-screen, answer it
```

Watch the server:

```bash
journalctl -u relay -f
```

You should see `device connected`, `rtc stats`, and never any plaintext.

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Client shows "Gateway offline" | Gateway service killed | Re-check §3.2 of the QA doc; verify the persistent notification is present |
| Calls never ring on the client | `ROLE_DIALER` not granted | Gateway step 2 |
| Call connects, no audio | Capture strategy failed | Check the call screen's *Gateway capture* line; see the reality matrix |
| Audio echoes / howls | Speaker volume too high in loopback | Lower gateway media volume; move the phones apart |
| "Pairing invalid — re-pair" | Room destroyed or JWT rejected | Unpair on both, pair again |
| Calls fail behind carrier NAT | No TURN | Check `TURN_URLS` and coturn; the client's metrics panel will show `RELAY` when TURN is in use |
| SMS arrives twice | Two builds installed (debug + release) | Uninstall one; both suffixes register the receiver |
| Server 401 on `/pair/init` | Bootstrap secret mismatch | The `-PrelayBootstrapSecret` build flag must equal `BOOTSTRAP_SECRET` in `.env` |

---

## 9. Legal note

You are relaying **your own** SMS and calls between **your own** two devices —
the same thing Samsung's "Call & text on other devices" and Google Messages
device pairing do. That is fine.

Recording or relaying calls that other people are on is regulated, and the rules
differ by jurisdiction: some places require only one party's consent, others
require all parties'. If anyone other than you is on the line, find out what
applies where you are before you use this.
