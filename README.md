# voxwin

**Talk to your Windows PC from your Android phone.**

voxwin is a tiny, self-hosted voice-control bridge. Hold a button on your phone,
say *"volume up"*, *"open notepad"*, *"type dear team"*, *"lock the computer"* —
and a small daemon running on your Windows machine carries it out. Speech is
transcribed **on the phone**; only a short JSON command ever crosses the
network, over your **local Wi-Fi/LAN**.

No cloud. No account. No telemetry. Your phone talks directly to your PC.

> **Status:** feature-complete and build-verified. The Android app builds and
> unit-tests green locally (`./gradlew test && assembleDebug` → 17 MB APK).
> The Rust daemon is format-clean and compiles on CI (`windows-latest`). See
> [docs/PROGRESS.md](docs/PROGRESS.md) for the full verification checklist.

---

## Table of contents

- [What it can do](#what-it-can-do)
- [How it works](#how-it-works)
- [Prerequisites](#prerequisites) — [Windows side](#windows-side-the-daemon) · [Android side](#android-side-the-app) · [Network](#network)
- [Build & run](#build--run) — [1. Daemon](#1-windows-daemon) · [2. App](#2-android-app) · [3. Connect & talk](#3-connect--talk)
- [Configuration reference](#configuration-reference)
- [Troubleshooting](#troubleshooting)
- [Security model](#security-model)
- [Limitations / not production](#limitations--not-production)
- [Project layout](#project-layout)

---

## What it can do

You speak a phrase; the app matches it (case-insensitive, whitespace-trimmed) to
one action and sends it. If nothing matches, it sends **nothing** and shows a
"didn't catch that" hint — it never guesses.

### Voice commands (complete list)

| Say | Action sent | What happens on Windows |
|-----|-------------|-------------------------|
| "volume up" / "louder" | `volume_up` | Presses the *Volume Up* media key |
| "volume down" / "quieter" | `volume_down` | Presses the *Volume Down* media key |
| "mute" / "unmute" | `mute` | Presses the *Mute* key (toggles) |
| "play" / "pause" / "play pause" | `media_play_pause` | Presses *Play/Pause* media key |
| "next" / "next track" / "skip" | `media_next` | Presses *Next Track* media key |
| "previous" / "back" / "last track" | `media_prev` | Presses *Previous Track* media key |
| "open X" / "launch X" / "start X" | `launch {app: X}` | Launches app **X** (see aliases below) |
| "type **TEXT**" | `type_text {text: TEXT}` | Types **TEXT** verbatim as keystrokes into the focused window (casing preserved) |
| "press **COMBO**" (e.g. "press control c") | `key {combo: ctrl+c}` | Presses a key combination |
| "scroll up" / "scroll down" | `scroll {direction, amount: 3}` | Scrolls the mouse wheel 3 steps |
| "lock" / "lock computer" / "lock the computer" | `lock` | Locks the Windows session |
| *(anything else)* | — | Nothing is sent; UI shows "didn't catch that" |

**Launch aliases** (matched case-insensitively; anything else is run as a raw
command or path):

| Alias | Runs |
|-------|------|
| `notepad` | `notepad` |
| `calculator` | `calc` |
| `explorer` / `files` | `explorer` |
| `browser` | `explorer https://www.google.com` (opens your default browser) |
| `terminal` | `wt` (Windows Terminal — errors if it isn't installed) |
| *anything else* | the spoken word(s) run as a program/path, e.g. "open spotify" → `spotify` |

**"press" normalization.** Spoken modifier words are rewritten to canonical
tokens and spaces become `+`: `control`→`ctrl`, `option`→`alt`,
`windows`/`super`/`meta`→`win`, `escape`→`esc`, `return`→`enter`, `spacebar`→`space`,
`del`/`delete`→`del`. So *"press control shift escape"* → `ctrl+shift+esc`. The
daemon understands modifiers `ctrl`/`alt`/`shift`/`win`, named keys
(`esc`, `enter`, `tab`, `space`, `backspace`, `delete`, `insert`, `home`, `end`,
`pageup`, `pagedown`, arrow keys `up`/`down`/`left`/`right`, `f1`–`f12`), and any
single character (e.g. the `c` in `ctrl+c`).

### App features

- **On-device speech-to-text** via `android.speech.SpeechRecognizer` (prefers the
  offline recognizer on Android 12+; falls back to the system/network recognizer
  otherwise).
- **Hold-to-talk or tap-to-talk** mic button.
- **Live transcript** of what it heard (partial + final).
- **Scrolling activity log** showing every command sent (`-> {json}`) and every
  daemon reply (`<- {json}`), capped at 200 lines.
- **Connection status dot**: grey = disconnected, amber = connecting, green =
  connected, red = error.
- **Ping** button for a quick health check once connected.
- **Settings persist** across restarts (host, port, token are saved to
  `SharedPreferences`).

### Daemon features

- Single WebSocket server; handles **multiple clients**, each on its own task.
- **Token gate**: a wrong/missing token gets an `unauthorized` error and the
  connection is closed.
- **Crash-resistant**: a malformed frame or a panicking handler is caught and
  turned into an `error` reply — it never brings the server down.
- **Prints its LAN IP + port + token** on startup so you know exactly what to
  type into the phone.

---

## How it works

```
  +-----------------------------+                 +------------------------------+
  |        Android phone        |                 |         Windows PC           |
  |                             |                 |                              |
  |  Jetpack Compose UI         |                 |   Rust daemon (tokio)        |
  |  "hold to talk"             |                 |                              |
  |        |                    |                 |   tokio-tungstenite          |
  |        v                    |                 |      (ws server :8787)       |
  |  android.speech             |                 |        |                     |
  |  SpeechRecognizer (STT)     |                 |        v                     |
  |        |                    |                 |   command dispatch           |
  |        v                    |                 |        |                     |
  |  phrase -> action mapping   |                 |        v                     |
  |        |                    |                 |   enigo  (SendInput)         |
  |        v                    |                 |   - keys / media / volume    |
  |  OkHttp WebSocket client    |   JSON over ws  |   - scroll / type            |
  |                             | =============>  |   std::process::Command      |
  |                             |                 |   - launch apps / lock       |
  +-----------------------------+   (LAN only)    +------------------------------+

                 phone  --ws://IP:8787-->  daemon
```

All traffic is JSON text frames over one WebSocket. Every command the phone
sends looks like:

```json
{ "type": "command", "token": "voxwin-dev", "action": "volume_up", "args": {} }
```

The daemon replies `hello` once on connect, then `ack` or `error` per command.
The full, canonical wire protocol is in **[docs/PROTOCOL.md](docs/PROTOCOL.md)**.

---

## Prerequisites

### Windows side (the daemon)

| Requirement | Detail |
|-------------|--------|
| **OS** | Windows 10 or 11 (x64). It uses Windows media virtual-key codes and `rundll32 user32.dll,LockWorkStation`, so it is Windows-only. |
| **Rust toolchain** | Install via [rustup](https://rustup.rs/). Stable channel, default `x86_64-pc-windows-msvc` target. `cargo` must be on your `PATH`. |
| **MSVC build tools** | The `msvc` target needs the **Visual Studio C++ Build Tools** (the "Desktop development with C++" workload, which provides `link.exe`). rustup will point you to these if they're missing. |
| **An interactive desktop session** | `enigo` injects input into the active session via `SendInput`. The daemon must run as **you**, logged in to the desktop — not as a headless background service with no session. |
| **Firewall allowance** | Inbound TCP on the daemon's port (default **8787**) must be allowed for your **Private** network profile. Windows usually prompts the first time; otherwise add a rule (see Troubleshooting). |
| *(optional)* **Admin rights** | Only needed if you want to drive **elevated** windows. Windows UIPI blocks synthetic input from a normal process into an elevated app; to type into an admin window the daemon itself must run elevated. Volume/media keys, launching, and lock work fine without admin. |
| *(optional)* **Windows Terminal** | Only for the `terminal` launch alias (`wt`). |

### Android side (the app)

**To build the app you need:**

| Requirement | Detail |
|-------------|--------|
| **JDK 17** | Android Gradle Plugin 8.5.2 requires **JDK 17**. JDK 8/11 will not work. Point `JAVA_HOME` at a JDK 17 (e.g. Temurin 17). |
| **Android SDK** | Platform **API 34** + matching build-tools, and **platform-tools** (for `adb`). Easiest via **Android Studio** (Hedgehog or newer). Create `android/local.properties` with `sdk.dir=C:/path/to/Android/Sdk` — **use forward slashes** (backslashes are parsed as escape sequences and break the build). This file is git-ignored and machine-specific. |
| **Gradle** | **Not needed separately** — the Gradle **wrapper is committed** (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, pinned to Gradle 8.7). The first run downloads the Gradle 8.7 distribution automatically. |
| **Internet (first build only)** | To fetch Gradle 8.7, the Android Gradle Plugin, and the AndroidX/Compose/OkHttp dependencies from Google's and Maven Central. |

**To run the app you need a device/emulator with:**

| Requirement | Detail |
|-------------|--------|
| **Android 8.0+ (API 26)** | `minSdk = 26`. |
| **A speech-recognition engine** | On-device recognition (`createOnDeviceSpeechRecognizer`) is used on **Android 12+** when available; the engine's offline language pack may need downloading (Settings → System → Languages & input → On-device speech recognition). On older/other devices it falls back to the **network** recognizer, which needs internet and the Google app / Google Speech Services. |
| **Microphone** | The app requests the `RECORD_AUDIO` runtime permission on first use. |
| **Same LAN as the PC** | Same Wi-Fi/subnet, with cleartext `ws://` reachable (see Network). |

### Network

- Phone and PC must be on the **same local network / subnet** (e.g. both on
  `192.168.0.x`).
- The Wi-Fi must not have **client/AP isolation** enabled (common on guest
  networks), which blocks device-to-device traffic.
- Traffic is plain `ws://` (cleartext). The app ships a network-security config
  permitting cleartext so you can point it at any LAN IP you type.

---

## Build & run

### 1. Windows daemon

```powershell
cd windows-daemon
cargo run --release
```

The binary is produced at `target\release\voxwin-daemon.exe`. On startup it
prints something like:

```
voxwin daemon listening on 0.0.0.0:8787
  -> enter this in the phone app:  192.168.0.42:8787
  -> auth token: voxwin-dev (default - set VOXWIN_TOKEN to change)
```

Note the **IP:port** and the **token** — you'll type them into the phone.

Override the defaults with environment variables:

```powershell
# PowerShell
$env:VOXWIN_TOKEN="my-secret"; $env:VOXWIN_PORT="9000"; .\target\release\voxwin-daemon.exe
```

```bash
# Git Bash
VOXWIN_TOKEN=my-secret VOXWIN_PORT=9000 ./target/release/voxwin-daemon.exe
```

### 2. Android app

```bash
cd android
# One-time: create local.properties pointing at your SDK (git-ignored).
#   Use forward slashes even on Windows — backslashes break the .properties parser.
#   echo "sdk.dir=C:/Users/you/AppData/Local/Android/Sdk" > local.properties

./gradlew test           # run the CommandParser unit tests (no device needed)
./gradlew assembleDebug  # build app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug   # build + install onto a connected device (adb)
```

On Windows `cmd`/PowerShell use `gradlew.bat` instead of `./gradlew`. Or simply
open the `android/` folder in **Android Studio** and press **Run**.

### 3. Connect & talk

1. Make sure the daemon is running and the phone is on the **same Wi-Fi**.
2. Open the **Voxwin** app. Enter the **Host/IP** (e.g. `192.168.0.42`), the
   **Port** (`8787`), and the **Token** (`voxwin-dev` unless you changed it).
   These are remembered for next time.
3. Tap **Connect**. The dot turns green and the daemon's `hello` appears in the
   log. (Tap **Ping** to confirm round-trips.)
4. **Hold the mic button**, speak a command, release. The transcript shows what
   it heard; the log shows the JSON sent and the daemon's `ack`.

---

## Configuration reference

**Daemon environment variables**

| Variable | Default | Meaning |
|----------|---------|---------|
| `VOXWIN_TOKEN` | `voxwin-dev` | Shared secret the phone must send on every command. |
| `VOXWIN_PORT` | `8787` | TCP port to listen on (binds `0.0.0.0`). |

**App settings** (entered in the UI, saved to `SharedPreferences`)

| Field | Default | Meaning |
|-------|---------|---------|
| Host / IP | `192.168.0.` | The daemon's LAN IP (as printed on startup). |
| Port | `8787` | Must match `VOXWIN_PORT`. |
| Token | `voxwin-dev` | Must match `VOXWIN_TOKEN`. |

---

## Troubleshooting

| Symptom | Likely cause & fix |
|---------|--------------------|
| App stuck on **CONNECTING** / goes **ERROR** | Firewall is blocking the port, wrong IP, or AP isolation. Allow the port (below), re-check the IP the daemon printed, and confirm both devices are on the same subnet. |
| Daemon replies **`unauthorized`** and disconnects | Token mismatch. The app's Token field must equal the daemon's `VOXWIN_TOKEN` (default `voxwin-dev`). |
| Connected, but **volume/media do nothing** | Some keyboards/apps swallow media keys; verify on the desktop. Confirm the `ack` arrives in the log (daemon side worked). |
| **`type`/`press` do nothing in an admin app** | Windows UIPI blocks synthetic input into **elevated** windows from a non-elevated process. Run the daemon elevated, or target a non-admin window. |
| **"Speech recognition is not available"** | The device has no recognizer / offline pack. Install/enable the Google app or an on-device speech pack (Settings → Languages & input → On-device speech recognition). |
| **`terminal` fails** | Windows Terminal (`wt`) isn't installed. Install it or use a different app. |
| **Gradle build fails on JDK version** | AGP 8.5.2 needs **JDK 17**. Set `JAVA_HOME` to a JDK 17. |
| **`SDK location not found`** | Create `android/local.properties` with `sdk.dir=C:/path/to/Android/Sdk` — use **forward slashes** (backslashes are parsed as escape sequences by Java's `.properties` parser and will cause a cryptic build failure). |

**Allow the port through Windows Firewall (PowerShell, as admin):**

```powershell
New-NetFirewallRule -DisplayName "voxwin" -Direction Inbound -Action Allow `
  -Protocol TCP -LocalPort 8787 -Profile Private
```

---

## Security model

voxwin is deliberately simple and makes explicit trade-offs:

- **Shared token only.** Every command carries a secret string; a missing/wrong
  one is rejected and the socket closed. This is a basic gate, **not** real
  authentication — there's no per-device identity, no rotation, no rate limiting.
- **LAN only.** Intended to listen only on your local network. **Do not**
  port-forward it or expose it to the internet.
- **Plain `ws://`, unencrypted.** Anyone who can sniff your LAN can read the
  commands *and the token*. Only use it on a network you trust.
- **Launched commands are not sandboxed.** "open X" with an unknown alias runs
  the spoken text as a program/path. Since any connected, authorized client can
  do this, treat the token as a password to your desktop.

## Limitations / not production

This is a small personal/hobby project and a tech demo. It is **not**
production-grade: no TLS (`wss://`), no hardened auth, no command sandboxing,
limited input validation, hold-to-talk only (no wake word), and launch aliases
are hard-coded (no user config file yet). Run it at your own risk on a trusted
LAN. Planned improvements are tracked in [docs/PROGRESS.md](docs/PROGRESS.md).

## Project layout

```
voxwin/
  README.md                  <- you are here
  docs/
    PROTOCOL.md              <- canonical wire protocol
    PROGRESS.md              <- status + roadmap
  windows-daemon/            <- Rust daemon (see its own README)
    Cargo.toml
    src/{main,protocol,actions}.rs
  android/                   <- Kotlin/Compose app (see its own README)
    gradlew / gradlew.bat / gradle/wrapper/
    app/src/main/java/dev/voxwin/app/
      MainActivity.kt  SpeechController.kt  CommandParser.kt
      VoxwinClient.kt  Models.kt
    app/src/test/java/dev/voxwin/app/CommandParserTest.kt
```

Per-half build details: [windows-daemon/README.md](windows-daemon/README.md)
and [android/README.md](android/README.md).

## Continuous integration

[.github/workflows/ci.yml](.github/workflows/ci.yml) builds both halves on
hosted runners on every push/PR:

- **Rust daemon** on `windows-latest` — `cargo fmt --check`, clippy,
  `cargo build --release`, `cargo test`, and uploads `voxwin-daemon.exe`.
- **Android app** on `ubuntu-latest` — JDK 17 + Android SDK, `./gradlew test`
  and `assembleDebug`, and uploads `app-debug.apk`.

These hosted runners are also the authoritative build signal for the Rust side,
since an enterprise-managed dev machine may block running freshly compiled
native binaries (see [docs/PROGRESS.md](docs/PROGRESS.md)).
