# Getting an installable APK

One APK installs on every phone. The role picker on first launch decides which
one is the Sender and which are Receivers.

---

## Why I cannot hand you the APK from here

Building an Android app needs the Android SDK (platform 35 + build-tools), and
that only comes from `dl.google.com`. My sandbox blocks that host — every
request returns a stub. Java and Maven Central are reachable; the SDK is not.

So there are two routes, and the first one means you install nothing.

---

## Route A — GitHub Actions (recommended)

`.github/workflows/build-apk.yml` is ready. Push the repo, and every push
produces a downloadable APK.

### 1. Push

```bash
cd "SMS & call bridge"
git init && git add . && git commit -m "Relay bridge"
git branch -M main
git remote add origin git@github.com:<you>/relay-bridge.git
git push -u origin main
```

> Make the repository **private**. The bootstrap secret ends up compiled into
> the APK, and the workflow reads it from repository secrets.

### 2. Configure

**Settings → Secrets and variables → Actions**

| Kind | Name | Value | Required |
|---|---|---|---|
| Variable | `RELAY_SERVER_URL` | `https://relay.example.com` | yes |
| Secret | `RELAY_BOOTSTRAP_SECRET` | same as `BOOTSTRAP_SECRET` in the server `.env` | yes |
| Secret | `GOOGLE_SERVICES_JSON` | `base64 -w0 google-services.json` | for FCM wake |
| Secret | `KEYSTORE_BASE64` | `base64 -w0 release.jks` | for release builds |
| Secret | `KEYSTORE_PASSWORD` | | with keystore |
| Secret | `KEY_ALIAS` | | with keystore |
| Secret | `KEY_PASSWORD` | | with keystore |

Without the last four you still get a working debug APK.

### 3. Download

**Actions → Build APK → latest run → Artifacts → `relay-apk-N`**

Unzip, then install the same file on both phones:

```bash
adb install -r app-debug.apk
```

Or copy it to each phone and tap it (allow "Install unknown apps" once).

---

## Route B — build it locally

Needs about 8 GB of disk and one download of roughly 2 GB.

1. **JDK 17** — [Temurin 17](https://adoptium.net/temurin/releases/?version=17).
   AGP 8.5 will not run on 11 or 21.
2. **Android Studio** (Ladybug or newer), or just the command-line tools plus:
   ```
   sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
   ```
3. Build:
   ```bash
   cd android
   gradle wrapper --gradle-version 8.9     # once, generates gradlew
   ./gradlew :app:assembleDebug \
     -PrelayServerUrl=https://relay.example.com \
     -PrelayBootstrapSecret=<your BOOTSTRAP_SECRET>
   ```
4. The APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`.

---

## What I need from you either way

These are things only you can produce. Give me any of them and I will wire
them in; without them the build still runs, with the noted gaps.

| # | Thing | Why | If missing |
|---|---|---|---|
| 1 | **A domain + a small VPS** | The relay server has to be reachable from both phones. 1 vCPU / 1 GB is plenty. | Nothing connects at all |
| 2 | **`RELAY_SERVER_URL`** | Compiled into the APK | Points at the placeholder `relay.example.com` |
| 3 | **`BOOTSTRAP_SECRET`** | `openssl rand -hex 32`. Goes in the server `.env` **and** the build flag; they must match | Sender cannot create a room |
| 4 | **`JWT_SECRET`** | `openssl rand -hex 48`, server only | Server refuses to start in production mode |
| 5 | **coturn + `TURN_STATIC_AUTH_SECRET`** | Calls between two mobile networks almost always need a TURN relay | SMS works; most calls fail to connect |
| 6 | **Firebase project + `google-services.json`** | Data-only wake pushes. Both roles use the same package `com.relay.app`, so **one** Android app entry | Calls only ring if the app happens to be awake; SMS arrives late |
| 7 | **Firebase service-account JSON** | Server-side, to send those pushes | Same as above |
| 8 | **A release keystore** (optional) | `keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias relay` | Debug signature — installs fine, but updates need an uninstall first |

### Quick generator

```bash
echo "BOOTSTRAP_SECRET=$(openssl rand -hex 32)"
echo "JWT_SECRET=$(openssl rand -hex 48)"
echo "TURN_STATIC_AUTH_SECRET=$(openssl rand -hex 32)"

keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 \
  -validity 10000 -alias relay
base64 -w0 release.jks > release.jks.b64
base64 -w0 google-services.json > gsj.b64
```

---

## Expect the first build to fail

This codebase has never been compiled — there is no Android SDK in my
environment to do it with. A project this size that has not seen a compiler
usually needs one or two rounds of small fixes: a missing import, a dependency
version that moved, a Compose API that changed signature.

**Paste me the Gradle error output and I will fix it.** The CI log is the
fastest way to get there, because it fails in about four minutes and the log is
a permalink you can copy.

---

## After it installs

1. Open the app on the phone **with the SIM** → choose **Sender**.
2. Work down its checklist: permissions → dialer role → battery → **Create pairing code**.
3. Open the app on every other phone → choose **Receiver** → type the 8-character code.
4. **Compare the six-digit verification number on both screens.** If they differ,
   remove that receiver — someone is between you.
5. Repeat step 3 for as many receivers as you want; the same code works until it
   expires or you revoke it.

Full operating detail is in `docs/04-SETUP.md`.
