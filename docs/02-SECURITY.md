# Agent 5 — Security Specialist

## 1. Threat model

| Adversary | Capability | Mitigated by |
|---|---|---|
| Passive network observer | Reads all traffic between handsets and server | TLS 1.2+ transport, AES-256-GCM payloads inside it |
| **Malicious / compromised relay server** | Full read-write on every socket, can drop, reorder, replay, relabel | E2EE with keys it never held; AAD binds event type; replay window; directional keys |
| TURN operator | Sees relayed media packets | DTLS-SRTP; keys negotiated end to end, TURN sees ciphertext |
| Google (FCM) | Sees every push | Data-only pushes containing no user content — just `{type, event, room, urgent}` |
| Stolen unlocked handset | Full app access | Out of scope; the device is the trust boundary |
| Stolen locked handset | Offline disk access | Root key wrapped by a Keystore/StrongBox key; message cache encrypted at rest |
| MITM at pairing | Substitutes their own QR | 6-digit SAS compared on both screens |

The server is designed to be **operable by someone you do not fully trust**,
because that is the realistic deployment: a cheap VPS.

## 2. Cryptographic inventory

| Purpose | Primitive | Parameters |
|---|---|---|
| Payload confidentiality + integrity | AES-256-GCM | 96-bit random IV, 128-bit tag |
| Key derivation | HKDF-SHA256 | salt = `roomId`, per-direction `info` strings |
| At-rest cache | AES-256-GCM | same key schedule, single-direction, no freshness window |
| Root-key wrapping | AES-256-GCM in Android Keystore | StrongBox where available |
| Device auth | JWT HS256 | 30-day TTL, `iss`/`aud` pinned |
| TURN credentials | HMAC-SHA1 | RFC-style `expiry:user`, 1-hour TTL |
| Pairing code | CSPRNG, Crockford Base32 | 9 symbols ≈ 45 bits, single use, 5-minute TTL |
| SAS | HKDF output → 6 decimal digits | ~20 bits, adequate for a human-compared code |
| Media | DTLS-SRTP (libwebrtc) | ECDSA certificates, generated per call |

### 2.1 Why directional keys

`K_g2c ≠ K_c2g`. A recorded gateway→client envelope therefore cannot be replayed
*at the gateway*: the gateway decrypts with `K_c2g`, and the captured ciphertext
was produced under `K_g2c`. Reflection attacks are structurally impossible rather
than merely detected.

### 2.2 Why AAD includes the event name

The AAD is `v|ev|sq|ts`. Without it, a hostile server could take an envelope the
gateway emitted as `sms:inbound` and re-emit it on the `call:hangup` channel. The
ciphertext would still authenticate and the client would parse a JSON body that
happens to be schema-compatible. Binding `ev` into the AAD means any relabelling
fails the tag check.

### 2.3 Replay window

`ReplayWindow` in `CryptoBox.kt` is an RFC 6479-style bitmap over the last 1024
sequence numbers. It tolerates the reordering a mobile network genuinely
produces while rejecting duplicates. Combined with a ±120 s freshness window,
a captured envelope has a two-minute usefulness horizon and can be used once.

### 2.4 Nonce discipline

A repeated `(key, IV)` pair under GCM is catastrophic — it leaks the XOR of two
plaintexts and, worse, the authentication subkey. `CryptoBox` draws a fresh
12-byte IV from `SecureRandom` on every `seal`. It never derives an IV from a
counter, because a counter that resets (process restart, restore from backup)
reuses nonces silently. At 96 random bits the birthday bound is ~2^32 messages
per key, far beyond any realistic pairing lifetime.

## 3. TURN HMAC credentials

`server/src/turn.js` implements the coturn REST API scheme:

```
username   = "<unix-expiry>:<roomId>"
credential = base64( HMAC-SHA1( static-auth-secret, username ) )
```

coturn configuration:

```conf
listening-port=3478
tls-listening-port=5349
fingerprint
use-auth-secret
static-auth-secret=<TURN_STATIC_AUTH_SECRET>
realm=relay.example.com
no-multicast-peers
no-tcp-relay
denied-peer-ip=0.0.0.0-0.255.255.255
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=169.254.0.0-169.254.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.168.0.0-192.168.255.255
denied-peer-ip=127.0.0.0-127.255.255.255
total-quota=100
stale-nonce=600
cert=/etc/letsencrypt/live/relay.example.com/fullchain.pem
pkey=/etc/letsencrypt/live/relay.example.com/privkey.pem
```

The `denied-peer-ip` block matters: without it your TURN server is an open SSRF
proxy into your own private network.

The **username is the opaque `roomId`, never a phone number.** A TURN log leak
should not identify anyone.

## 4. Pairing verification (SAS)

After pairing, both devices display the same 6 digits derived from the root key.
If they differ, someone substituted the QR between the two screens — which
requires physical presence and is exactly the attack a QR cannot otherwise stop.

The gateway shows it in Settings; the client shows it immediately after scanning
and again in Settings. **Both apps tell the user to unpair if the codes differ**
rather than presenting it as an optional curiosity.

## 5. Server hardening checklist

- [ ] Run behind nginx or Caddy with TLS 1.2+ and HSTS
- [ ] `BOOTSTRAP_SECRET` and `JWT_SECRET` from `openssl rand -hex 48`, never committed
- [ ] Run as a non-root user with `NoNewPrivileges=yes` in the systemd unit
- [ ] Firewall: 443 (or your port) and TURN 3478/5349 only
- [ ] `fail2ban` on the pairing endpoints (rate limiting is already in-process)
- [ ] Log rotation with a short retention — the server logs metadata, and metadata is still sensitive
- [ ] Rotate `TURN_STATIC_AUTH_SECRET` quarterly; credentials expire hourly so rotation is transparent
- [ ] Back up nothing. There is nothing on this server worth restoring, and `rooms.json` is re-creatable by re-pairing

## 6. Client hardening checklist

- [ ] Enable certificate pinning in both `network_security_config.xml` files once your cert is stable — **pin two keys**, leaf and backup
- [ ] Ship release builds with R8 enabled (already configured)
- [ ] `allowBackup="false"` and empty data-extraction rules (already configured)
- [ ] Verify the SAS on first pairing
- [ ] Do not root the gateway handset just to get `VOICE_CALL` capture unless you accept that this removes the Keystore's hardware guarantees

## 7. Known limitations — stated plainly

1. **The server sees metadata.** Who is online, when, how many envelopes, and
   how large. Traffic analysis can infer conversation timing. Padding envelopes
   to fixed sizes would reduce this and is not implemented.

2. **FCM reveals wake timing to Google.** The push contains no content, but the
   fact that *something* arrived at a given moment is visible.

3. **The gateway handset is a single point of compromise.** It holds the SIM,
   the contacts, the SMS provider and a copy of the root key.

4. **No forward secrecy on the control plane.** The root key is long-lived; a
   future compromise of it decrypts previously captured envelopes. Adding a
   Double Ratchet would fix this and is the single highest-value improvement to
   make next. The *media* plane already has forward secrecy via per-call DTLS.

5. **The SAS is 20 bits.** Adequate against an attacker who must also physically
   intercept the QR, insufficient against an attacker who can force many pairing
   attempts. Re-pairing repeatedly after mismatches is not a safe workaround.
