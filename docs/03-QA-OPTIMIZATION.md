# Agent 6 — QA & Optimization

## 1. Audio capture reality matrix

**Read this before you file a bug about silent calls.**

Android does not expose cellular voice-call audio to ordinary applications.
`MediaRecorder.AudioSource.VOICE_CALL`, `VOICE_DOWNLINK` and `VOICE_UPLINK` are
guarded by `android.permission.CAPTURE_AUDIO_OUTPUT`, whose protection level is
`signature|privileged`. A normally-installed APK can never hold it. Samsung
additionally blocks these sources in the audio HAL on most retail One UI builds
even for privileged callers.

`CallAudioBridge.kt` therefore probes strategies in order and reports which one
actually opened, and the client call screen displays that answer.

| Strategy | Install type required | Audio quality | Covers | Notes |
|---|---|---|---|---|
| `VOICE_CALL` | System / privileged / Knox-signed | Excellent | Both legs | Modem-side AEC already applied — software AEC is disabled to avoid the "underwater" artefact |
| `VOICE_DOWNLINK` | System / privileged | Excellent | Far end only | Your voice reaches them via the gateway mic |
| `VOICE_COMMUNICATION` + speakerphone | **Ordinary APK** | Fair | Both, acoustically | Gateway must be in a quiet room |
| `MIC` + speakerphone | **Ordinary APK** | Poor | Both, acoustically | Last resort |

### Making loopback mode usable

The loopback strategies work because the gateway puts the cellular call on
speakerphone: the far end's voice leaves the loudspeaker, our `AudioRecord`
captures it, and our WebRTC playback goes back out of the same speaker, where the
modem's uplink microphone picks it up.

Getting acceptable results:

1. **Keep the gateway in a quiet, enclosed space** — a drawer works well and also
   solves the "don't touch this phone" problem.
2. **Do not max the speaker.** `CallAudioBridge.prepareRouting` sets voice-call
   volume to 80 % deliberately; clipping destroys the AEC reference signal and
   produces howling.
3. **Leave hardware AEC and NS enabled** (they are, via `JavaAudioDeviceModule`).
   `AcousticEchoCanceler` is what stops the feedback loop.
4. **Do not place the two phones next to each other** if the client is also in
   the room; you will get a second acoustic path the AEC knows nothing about.

## 2. Opus configuration

Applied in `WebRtcEngine.tuneOpus()`:

```
a=fmtp:111 minptime=10;useinbandfec=1;usedtx=0;stereo=0;sprop-stereo=0;
           maxaveragebitrate=32000;maxplaybackrate=48000;cbr=0
a=ptime:20
a=maxptime:60
```

| Parameter | Value | Reasoning |
|---|---|---|
| `maxplaybackrate` | 48000 | Full-band. The cellular leg is narrowband, but the *relay* leg should not add a second bandwidth reduction on top of it |
| `maxaveragebitrate` | 32000 | Transparent for speech; ~14 MB/hour, acceptable on mobile data |
| `useinbandfec` | 1 | Recovers isolated packet loss without retransmission — the single biggest quality win on a mobile link |
| `usedtx` | 0 | DTX off. Comfort noise during a relayed call is indistinguishable from a dropped bridge, and users hang up |
| `stereo` | 0 | Telephony is mono; stereo would double the bitrate for nothing |
| `ptime` | 20 ms | Standard frame. 10 ms halves latency but triples header overhead; 40 ms is audibly laggy on a doubled path |
| `cbr` | 0 | Variable bitrate; better quality at the same average |

Field trials (`WebRtcEngine.FIELD_TRIALS`):

```
WebRTC-Audio-Allocation/min:16000bps,max:40000bps/
WebRTC-Audio-NetEqDecelerationTargetLevelOffset/Enabled-85/
WebRTC-Audio-MinimizeResamplingOnMobile/Enabled/
```

The allocation floor stops the bandwidth estimator starving speech during
congestion. The NetEq offset trims jitter-buffer latency to ~85 ms, which is a
reasonable compromise given the call already traverses two legs.

### Latency budget

| Segment | Typical |
|---|---|
| Opus encode + framing | 20–26 ms |
| Network (P2P) | 15–60 ms |
| Network (TURN relay) | 40–120 ms |
| NetEq jitter buffer | 40–85 ms |
| Decode + playout | 10–15 ms |
| **Relay leg total** | **≈ 85–250 ms** |
| Cellular leg (unavoidable) | 100–300 ms |

Above roughly 400 ms round trip, conversation starts to collide. The call screen's
metrics panel flags this: **HD** below 200 ms RTT and 1.5 % loss, **FAIR** below
400 ms, **POOR** above.

## 3. Samsung / Knox battery survival

Samsung layers three independent kill mechanisms on top of AOSP. Only the first
can be waived programmatically.

### 3.1 Programmatic — Doze exemption

```kotlin
Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    .setData(Uri.parse("package:$packageName"))
```

Requires `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in the manifest. Both apps
request it from their setup screens and show live status.

### 3.2 Manual — One UI, in this order

1. **Settings → Battery → Background usage limits → Never sleeping apps** → add both apps
2. **Settings → Battery → Background usage limits → Deep sleeping apps** → remove both apps if present
3. **Settings → Battery → More battery settings** → turn off **Adaptive battery**
4. **Settings → Apps → [app] → Battery → Unrestricted**
5. **Settings → Device care → Auto optimisation** → turn off **Close apps that aren't in use**
6. **Settings → Device care → ⋮ → Automation** → turn off **Auto restart at set times**
7. **Recents → app card → ⋮ → Keep open for quick launching** (lock the app)

`SamsungBatterySettings.openPowerSettingsIntent()` deep-links to Device Care with
a resolve check and a graceful AOSP fallback, because One UI renames these
activities between major versions.

### 3.3 Knox / MDM path

If you control the devices through Knox:

- Add both apps to the **Knox battery whitelist** via `ApplicationPolicy.setBatteryOptimizationWhitelist`
- Use **Knox Custom / Knox Service Plugin** to grant `CAPTURE_AUDIO_OUTPUT`, which unlocks true `VOICE_CALL` capture
- Set the apps as **persistent** via `ApplicationPolicy.setApplicationStateList`

Without an enterprise Knox licence none of this is available, and loopback mode
is the honest ceiling.

### 3.4 Defence in depth already in the code

| Mechanism | File |
|---|---|
| Foreground service, `START_STICKY` | `RelayForegroundService.kt` |
| `dataSync\|microphone\|phoneCall` FGS types | `AndroidManifest.xml` |
| High-priority data-only FCM wake | `fcm.js`, `GatewayMessagingService.kt` |
| Boot + package-replaced restart | `BootReceiver.kt` |
| Self-resurrect broadcast on `onDestroy` | `RelayForegroundService.kt` |
| 30 s heartbeat with reconnect | both services |
| Socket.IO exponential backoff, capped at 30 s | `SignalingClient.kt` |
| Server-side offline queue (500/room) | `store.js` |
| Partial wake lock **only during calls** | `RelayForegroundService.kt` |

The wake lock is scoped to calls deliberately. A permanent partial wake lock
costs several percent of battery per hour and the foreground service alone keeps
the socket alive between calls.

## 4. Test plan

### 4.1 Protocol

| # | Test | Expected |
|---|---|---|
| P1 | Tamper one byte of `ct` in transit | Receiver logs REJECTED, `rejectedEnvelopes` increments, nothing reaches the UI |
| P2 | Replay a captured envelope | Rejected as duplicate sequence |
| P3 | Relabel `sms:inbound` as `call:hangup` server-side | Rejected — server drops on `ev` mismatch, client would reject on AAD |
| P4 | Set device clock +5 minutes | Envelopes rejected as outside the freshness window |
| P5 | Re-pair, then load the old message cache | Cache fails to open, is deleted, app starts clean |
| P6 | Compare SAS on both devices | Identical 6 digits |

### 4.2 SMS

| # | Test | Expected |
|---|---|---|
| S1 | 160-char ASCII inbound | One bubble, correct text |
| S2 | 500-char inbound with emoji | One bubble, correctly reassembled (multipart + UCS-2) |
| S3 | Outbound multipart | Single QUEUED → SENT → DELIVERED progression, not one per part |
| S4 | Outbound with radio off | FAILED with `RESULT_ERROR_RADIO_OFF` |
| S5 | Inbound while client offline | Queued server-side, FCM wake, delivered on reconnect |
| S6 | Inbound while gateway app killed | Broadcast receiver restarts the service, message relays |
| S7 | Dual-SIM inbound | `simSlot` matches the receiving SIM |
| S8 | Stock Messages app still receives everything | Yes — we never `abortBroadcast()` |

### 4.3 Calls

| # | Test | Expected |
|---|---|---|
| C1 | Incoming call, client screen on | Rings within 2 s, full-screen UI |
| C2 | Incoming call, client locked | Full-screen intent wakes the screen over the keyguard |
| C3 | Incoming call, client in Doze 30+ min | FCM wake, ring within ~5 s |
| C4 | Answer from the notification action | Bridge opens, audio both ways |
| C5 | Decline from the lock screen | Cellular call rejected, no Activity launched |
| C6 | Emergency number dialled on the gateway | Bridge stands down, no interception |
| C7 | Wi-Fi → LTE mid-call | ICE restart, ≤ 3 s gap |
| C8 | Both peers behind symmetric NAT | Falls back to TURN relay, call completes |
| C9 | DTMF into an IVR | Tones register |
| C10 | 30-minute call | No leak, no drift, stats stable |
| C11 | Second call arrives during a bridged call | Ignored with a log line; one bridge at a time |

### 4.4 Resilience

| # | Test | Expected |
|---|---|---|
| R1 | Kill the server mid-call | Media survives (P2P), control resumes on reconnect |
| R2 | Restart the server | Rooms restored from `rooms.json`, JWTs still valid |
| R3 | Airplane mode on the client for 10 min | Backlog flushed on reconnect |
| R4 | Reboot both devices | Both services auto-start, pairing intact |
| R5 | Force-stop the gateway app | Next inbound SMS restarts it via the broadcast receiver |
| R6 | Fill the offline queue past 500 | Oldest dropped, newest retained, no OOM |

### 4.5 UI

| # | Test | Expected |
|---|---|---|
| U1 | Scroll a 500-message thread | 120 fps on S26 Ultra, no dropped frames from photo decoding |
| U2 | Dock tab switching | Highlight springs, no cross-fade flicker |
| U3 | Ambient orbs over 10 minutes | No visible loop repetition, negligible battery cost |
| U4 | Contact with no photo | Deterministic name gradient + initials, stable across launches |
| U5 | API 29 device (blur unavailable) | Heavier scrim keeps text contrast; nothing looks broken |
| U6 | TalkBack over the call controls | Every control announces its action |

## 5. Performance notes

- **Contact photos** are downscaled to 128 px JPEG q70 on the gateway (~6 KB) and
  LRU-cached after decode on the client. Decoding a full-size photo per
  recomposition of a `LazyRow` item is the classic way to lose 60 fps here.
- **Ambient orbs** are four radial gradients drawn into one layer and blurred
  once, not four separate blur passes.
- **The waveform** is a single `Canvas` with `drawRoundRect` per bar — no
  per-bar composable, which would allocate 36 layout nodes per message.
- **Stats polling** is 1 Hz. `getStats()` is not free; 10 Hz measurably raises
  CPU during a call for no perceivable benefit.
- **The message store** is memory + one encrypted file, not a database. At 5 000
  messages that is under 1 MB and avoids an entire Room dependency plus its
  migration surface.
