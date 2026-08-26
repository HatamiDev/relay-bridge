# Getting the APK

**I cannot build it in my environment.** Building an Android app requires the
Android SDK and the Android Gradle Plugin, and both come only from
`dl.google.com` / `maven.google.com`. Both hosts are blocked for me — I probed
them plus six mirrors; every one returns a stub. Java, Maven Central and npm all
work; the Android toolchain specifically does not.

So the APK has to be built somewhere with internet access to Google. Two ways,
and the first needs nothing installed.

---

## Fastest — GitHub builds it for you (~6 minutes)

Open PowerShell in this folder and run:

```powershell
.\push-to-github.ps1
```

It creates a **private** repository, pushes, and opens the Actions tab. The APK
lands under **Actions → the run → Artifacts → `relay-apk-N`**.

Needs `git`, and `gh` (`winget install GitHub.cli`) if you want the repo created
for you. Without `gh` the script prints the two commands to run yourself.

### Then add three settings

`Settings → Secrets and variables → Actions`

| Type | Name | Value |
|---|---|---|
| Secret | `RELAY_BOOTSTRAP_SECRET` | the same `BOOTSTRAP_SECRET` as in `server/.env` |
| Variable | `RELAY_SERVER_URL` | `https://hatamidev.com` — already the default |
| Secret | `GOOGLE_SERVICES_JSON` | `base64 -w0 google-services.json` |

Without the first one the Sender cannot create a pairing room. Without the third
you get a working app whose calls only ring when it happens to be awake.

---

## If you already have Android Studio

Open the `android` folder, let it sync, then **Build → Build APK(s)**.

Or from a terminal, once, to create the Gradle wrapper:

```powershell
cd android
gradle wrapper --gradle-version 8.9
.\gradlew :app:assembleDebug -PrelayBootstrapSecret=<your BOOTSTRAP_SECRET>
```

Output: `android\app\build\outputs\apk\debug\app-debug.apk`

Needs **JDK 17** specifically. AGP 8.5 will not run on 11 or 21.

---

## Handing it to someone else to build

`dist\` has two ready packages:

- **`relay-bridge.bundle`** — the whole git repository in one file, with history:
  ```bash
  git clone relay-bridge.bundle relay && cd relay
  ```
- **`relay-bridge-source.zip`** — plain source, no git.

Neither contains a secret; `.gitignore` keeps `.env`, keystores and
`google-services.json` out.

---

## Installing, once you have the APK

Same file on every phone.

```powershell
adb install -r app-debug.apk
```

Or copy it across and tap it — allow "Install unknown apps" once.

1. Phone **with the SIM** → open the app → choose **Sender**
2. Work down its checklist: permissions → dialer role → battery → **Create pairing code**
3. Every other phone → **Receiver** → type the 8-character code
4. **Compare the six-digit verification number on both screens.** Different numbers
   mean someone is between you — remove that receiver.

Full operating detail: `docs/04-SETUP.md`. Server setup: `deploy/README.md`.

---

## Expect the first build to fail

This is 12,600 lines of Kotlin across 55 files that has never been through a
compiler — there is no Android SDK in my environment to run one. What I could
verify statically, I did: every `colors.*` and `dimens.*` reference resolves
against the theme, no dead identifiers survive, all XML parses, and the whole
Node backend was executed against a real Redis instance.

What that does not catch is exactly what a Kotlin compiler catches. A project
this size normally needs one or two rounds of small fixes — a missing import, a
Compose API whose signature moved.

**Send me the compiler errors and I will fix them.** The CI log is the quickest
route: it fails in about four minutes and the URL is copy-pasteable.
