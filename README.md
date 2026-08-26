# Dual-Android SMS & WebRTC Audio Call Relay System

Relay cellular SMS and voice calls from a **Gateway** device that holds the SIM
(Galaxy Note 10+) to a **Client** device that does not (Galaxy S26 Ultra), over
an end-to-end-encrypted WebSocket control plane and a WebRTC audio media plane.

```
┌──────────────────┐        ┌──────────────────────┐        ┌──────────────────┐
│  GATEWAY (SIM)   │        │  SIGNALING SERVER    │        │  CLIENT (no SIM) │
│  Galaxy Note 10+ │◄──────►│  Node.js + Socket.IO │◄──────►│  Galaxy S26 Ultra│
│                  │  WSS   │  + TURN cred issuer  │  WSS   │                  │
│  SmsReceiver     │        │  + FCM waker         │        │  Compose UI      │
│  SmsManager      │        │  (zero-knowledge)    │        │  WebRTC player   │
│  InCallService   │        └──────────────────────┘        └──────────────────┘
│  WebRTC audio    │                                                  ▲
└────────┬─────────┘                                                  │
         │                    WebRTC / SRTP-DTLS (Opus 48 kHz)        │
         └──────────────────────────────────────────────────────────►─┘
                     (P2P when possible, TURN relay as fallback)
```

## Repository layout

```
.
├── docs/                       Agent 1 / 5 / 6 deliverables
│   ├── 01-ARCHITECTURE.md      Protocol flow, permissions, E2EE payloads
│   ├── 02-SECURITY.md          AES-256-GCM, HKDF, TURN HMAC, threat model
│   ├── 03-QA-OPTIMIZATION.md   Knox / battery bypass, Opus tuning, test plan
│   └── 04-SETUP.md             Build + run instructions end to end
├── server/                     Agent 2 — Node.js signaling + relay backend
│   ├── server.js
│   ├── package.json
│   ├── .env.example
│   └── src/{config,logger,crypto,auth,store,turn,fcm,signaling}.js
└── android/
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── gradle.properties
    ├── core/                   Shared: crypto, socket client, WebRTC engine
    ├── gateway/                Agent 3 — Note 10+ app (SIM holder)
    └── client/                 Agent 4 — S26 Ultra app (Compose glass UI)
```

## Quick start

```bash
# 1. Backend
cd server && cp .env.example .env && npm install && npm start

# 2. Android
cd android && ./gradlew :gateway:assembleDebug :client:assembleDebug
```

Then follow `docs/04-SETUP.md` for pairing, role grants and battery whitelisting.

## Honest capability notes

Read `docs/03-QA-OPTIMIZATION.md` §"Audio capture reality matrix" before you
build. Android does **not** expose the cellular voice-call audio stream to
ordinary apps. The gateway implements three selectable capture strategies
(`VOICE_CALL`, `VOICE_COMMUNICATION` speakerphone loopback, `MIC` loopback) and
auto-negotiates the best one your device/firmware actually permits. The code
detects and reports which one succeeded instead of silently producing silence.
