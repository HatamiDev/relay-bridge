# Agent 1 — System Architect

## 1. Roles and trust boundaries

| Component | Device | Trust | Holds E2EE keys |
|---|---|---|---|
| Gateway  | Galaxy Note 10+ (SIM present) | Trusted endpoint | Yes |
| Client   | Galaxy S26 Ultra (no SIM)     | Trusted endpoint | Yes |
| Server   | Node.js on your VPS           | **Untrusted relay** | **No** |
| TURN     | coturn                        | Untrusted relay (sees SRTP only) | No |

The server is a *zero-knowledge* rendezvous: it routes opaque ciphertext
envelopes between exactly two sockets that share a `roomId`, issues short-lived
TURN credentials, and wakes an offline peer over FCM. It can observe metadata
(who is online, envelope sizes, timing) but never plaintext SMS bodies, phone
numbers, SDP or ICE candidates.

## 2. Pairing and key establishment

Key material never traverses the server. It is transported out-of-band via QR.

```
GATEWAY                          SERVER                          CLIENT
   │                                │                               │
   │ 1. POST /pair/init             │                               │
   │    {deviceId, platform}        │                               │
   │───────────────────────────────►│                               │
   │◄───────────────────────────────│ {pairCode, roomId, expiresAt} │
   │                                │                               │
   │ 2. rootKey = CSPRNG(32 bytes)  │  (server never sees rootKey)  │
   │    render QR:                  │                               │
   │    relay://pair?h=<host>       │                               │
   │            &c=<pairCode>       │                               │
   │            &r=<roomId>         │                               │
   │            &k=<b64url rootKey> │                               │
   │                                │                               │
   │            ══════ QR scanned optically ══════════════════════► │
   │                                │                               │
   │                                │ 3. POST /pair/claim           │
   │                                │    {pairCode, deviceId, fcm}  │
   │                                │◄──────────────────────────────│
   │                                │──────────────────────────────►│
   │                                │   {jwt, roomId, iceServers}   │
   │ 4. POST /pair/finalize         │                               │
   │───────────────────────────────►│                               │
   │◄───────────────────────────────│ {jwt, roomId, iceServers}     │
```

`pairCode` is a 9-character Crockford-Base32 code, single-use, 5-minute TTL,
rate-limited, and compared in constant time.

### Key schedule (HKDF-SHA256, RFC 5869)

```
PRK       = HKDF-Extract(salt = roomId_utf8, ikm = rootKey)
K_g2c     = HKDF-Expand(PRK, info = "relay/v1/sms|sig gateway->client", L=32)
K_c2g     = HKDF-Expand(PRK, info = "relay/v1/sms|sig client->gateway", L=32)
K_sas     = HKDF-Expand(PRK, info = "relay/v1/sas",                    L=4)
```

`K_sas` renders a 6-digit **Short Authentication String** shown on both screens
after pairing. If the two numbers match, no man-in-the-middle intercepted the QR.

Directional keys mean a captured envelope can never be replayed back at its
originator.

## 3. Transport events (Socket.IO, namespace `/relay`)

Handshake: `io(url, { auth: { token: <JWT>, role: "gateway"|"client" } })`.

### 3.1 Control plane

| Event | Direction | Payload (after decryption) |
|---|---|---|
| `presence` | both → server → peer | `{role, online, battery, signalDbm, simState, ts}` |
| `sms:inbound` | gateway → client | `{id, from, body, ts, simSlot, threadId}` |
| `sms:outbound` | client → gateway | `{id, to, body, ts, simSlot}` |
| `sms:status` | gateway → client | `{id, state, errorCode?, ts}` |
| `sms:sync` | client → gateway | `{sinceTs, limit}` |
| `sms:sync:result` | gateway → client | `{messages:[…], hasMore}` |
| `contacts:sync` | client → gateway | `{}` |
| `contacts:result` | gateway → client | `{contacts:[{id,name,number,photoB64,pinned}]}` |

`sms:status.state ∈ { QUEUED, SENT, DELIVERED, FAILED }`.

### 3.2 Call plane

| Event | Direction | Payload |
|---|---|---|
| `call:incoming` | gateway → client | `{callId, from, displayName?, ts}` |
| `call:place` | client → gateway | `{callId, to}` |
| `call:answer` | client → gateway | `{callId}` |
| `call:reject` | client → gateway | `{callId, reason}` |
| `call:hangup` | either | `{callId, reason}` |
| `call:dtmf` | client → gateway | `{callId, tone}` |
| `call:mute` | client → gateway | `{callId, muted}` |
| `call:state` | gateway → client | `{callId, state, cause?, ts}` |

`call:state.state ∈ { RINGING, DIALING, CONNECTING, ACTIVE, HELD, ENDED }`.

### 3.3 WebRTC signaling plane

| Event | Direction | Payload |
|---|---|---|
| `rtc:offer` | gateway → client | `{callId, sdp}` |
| `rtc:answer` | client → gateway | `{callId, sdp}` |
| `rtc:ice` | either | `{callId, candidate:{sdpMid, sdpMLineIndex, candidate}}` |
| `rtc:renegotiate` | either | `{callId}` |
| `rtc:stats` | client → server | `{callId, rttMs, jitterMs, lossPct, bitrateKbps, codec}` |

The **gateway is always the offerer** — it owns the audio source, so it decides
the media direction and codec parameters. This removes glare handling entirely.

### 3.4 Full call sequence

```
 CELLULAR      GATEWAY                 SERVER                 CLIENT
    │             │                       │                      │
    │ ring ──────►│                       │                      │
    │             │ InCallService.onCallAdded()                  │
    │             │──── call:incoming ───►│──── (FCM wake) ─────►│
    │             │                       │                      │ full-screen
    │             │                       │                      │ CallScreen
    │             │◄─────────────────── call:answer ─────────────│
    │◄─ answer ───│                       │                      │
    │             │ CallAudioBridge.start()                      │
    │             │ PeerConnection.createOffer()                 │
    │             │──── rtc:offer ───────►│─────────────────────►│
    │             │◄──────────────────── rtc:answer ─────────────│
    │             │◄────── rtc:ice ──────►│◄────── rtc:ice ─────►│
    │             │                       │                      │
    │◄═══════ SRTP/DTLS Opus 48 kHz bidirectional ══════════════►│
    │             │──── call:state ACTIVE►│─────────────────────►│
    │             │                       │                      │
    │             │◄──────────────────── call:hangup ────────────│
    │◄─ disconnect│                       │                      │
    │             │──── call:state ENDED ►│─────────────────────►│
```

## 4. E2EE envelope structure

Every payload above is wrapped before it touches the socket:

```jsonc
{
  "v":   1,                    // envelope version
  "ev":  "sms:inbound",        // duplicated in AAD, binds type to ciphertext
  "sq":  8412,                 // monotonic per-direction sequence, replay guard
  "ts":  1774521600123,        // ms epoch, ±120 s freshness window
  "iv":  "8f2a…",              // base64url, 12 random bytes (never reused)
  "ct":  "9d01…"               // base64url, AES-256-GCM ciphertext ‖ 16-byte tag
}
```

* Cipher: **AES-256-GCM**, 96-bit IV, 128-bit tag.
* Key: `K_g2c` or `K_c2g` depending on sender.
* AAD: `utf8(v ‖ "|" ‖ ev ‖ "|" ‖ sq ‖ "|" ‖ ts)` — binds routing metadata so
  the server cannot re-label an envelope as a different event type.
* Plaintext: `utf8(JSON.stringify(payload))`.
* Replay: receiver keeps a sliding window of the last 1024 sequence numbers per
  direction and rejects duplicates or `sq` older than `maxSeen - 1024`.

## 5. Android manifest permissions

### 5.1 Gateway (Note 10+)

```xml
<!-- SMS relay -->
<uses-permission android:name="android.permission.RECEIVE_SMS"/>
<uses-permission android:name="android.permission.SEND_SMS"/>
<uses-permission android:name="android.permission.READ_SMS"/>
<uses-permission android:name="android.permission.RECEIVE_MMS"/>

<!-- Call interception (requires ROLE_DIALER at runtime) -->
<uses-permission android:name="android.permission.READ_PHONE_STATE"/>
<uses-permission android:name="android.permission.READ_PHONE_NUMBERS"/>
<uses-permission android:name="android.permission.CALL_PHONE"/>
<uses-permission android:name="android.permission.ANSWER_PHONE_CALLS"/>
<uses-permission android:name="android.permission.MANAGE_OWN_CALLS"/>
<uses-permission android:name="android.permission.READ_CALL_LOG"/>

<!-- Audio bridge -->
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>

<!-- Contacts mirroring -->
<uses-permission android:name="android.permission.READ_CONTACTS"/>

<!-- Survival -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL"/>
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
```

### 5.2 Client (S26 Ultra)

Same survival + audio set, **minus** all SMS/telephony permissions. The client
never touches a radio; it only plays and captures Opus over WebRTC.

## 6. Failure and reconnection model

| Condition | Behaviour |
|---|---|
| Client socket down, SMS arrives | Server queues envelope (bounded, 500/room), sends data-only FCM `wake`, flushes on reconnect |
| Gateway socket down | Client UI shows "Gateway offline", outbound SMS queued locally with `QUEUED` state |
| ICE fails | `iceRestart: true` renegotiation, then forced `iceTransportPolicy: "relay"` |
| Server restart | JWTs survive (stateless); rooms rebuilt from disk snapshot |
| Doze / App Standby | Foreground service + FCM high-priority data message + battery-optimization exemption |
