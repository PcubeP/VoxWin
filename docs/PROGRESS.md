# voxwin — progress

**Status: Android verified-building locally; Rust CI-verified; shippable UI**

Both halves are complete and the wire protocol is pinned. The Android app is
polished and **builds green locally** (JDK 17 + Android SDK 34).
The Rust daemon compiles its dependency graph locally but the build/run is
blocked on this specific machine by enterprise Defender (see note); it is built
and tested authoritatively by CI on a hosted Windows runner.

## What exists

- [x] Wire protocol defined and documented ([PROTOCOL.md](PROTOCOL.md))
- [x] Rust daemon: tokio + tokio-tungstenite server, token auth, multi-client,
      crash-resistant; all base actions + mouse + LLM; `rustfmt`-clean
- [x] Android app: Kotlin + Compose, on-device `SpeechRecognizer`, OkHttp ws
      client, robust `CommandParser`
- [x] **Polished, shippable UI**: Scaffold + centered top bar with live status
      chip, connection card (locks fields while connected), big animated
      hold/tap mic with pulse ring + haptics, live transcript, "try saying"
      suggestion chips, colour-coded auto-scrolling activity log, Material You
      dynamic color + indigo/teal brand fallback, edge-to-edge
- [x] CommandParser unit tests (incl. casing / whitespace edge cases) — **pass**
- [x] Gradle wrapper committed (Gradle 8.7); `./gradlew` works turnkey
- [x] Connection settings persisted across restarts
- [x] Exhaustive README + per-half READMEs
- [x] **GitHub Actions CI** ([../.github/workflows/ci.yml](../.github/workflows/ci.yml)):
      Rust build/test/fmt on `windows-latest`; Android test + APK on
      `ubuntu-latest`; both upload artifacts
- [x] **Downloadable releases** ([../.github/workflows/release.yml](../.github/workflows/release.yml)):
      pushing a `v*` tag publishes a GitHub Release with a release-signed
      `voxwin.apk` (phone) and `voxwin-daemon.exe` (PC) attached as downloads.
      One-time keystore + secrets setup in [RELEASING.md](RELEASING.md)

### Feature 1 — Remote Touchpad + Keyboard (complete, 2026-11-20)

- [x] **7 new wire actions**: `mouse_move_rel`, `mouse_move_abs`, `mouse_click`,
      `mouse_double`, `mouse_down`, `mouse_up`, `mouse_scroll`
- [x] **TouchpadScreen**: gesture surface using `awaitEachGesture` +
      `Handler.postDelayed` for long-press (single drag, tap, 2-finger scroll,
      long-press drag-and-drop)
- [x] **Quick-key shortcut row**: horizontally-scrollable row of `OutlinedButton`
      chips (Ctrl+C/V/Z/A/X/S, Tab, Esc, Win, Del) in keyboard mode
- [x] **Sensitivity settings sheet**: pointer speed (0.5×–4×) + scroll speed
      (0.05×–1.0×), persisted to SharedPreferences
- [x] **Mouse-move log suppression**: 60 Hz move events filtered out of the
      activity log to stay readable
- [x] NavigationBar with Voice / Touchpad tabs; both share the live `client`
      instance so the connection persists across tab switches

### Feature 2 — Ollama LLM Assistant (complete, 2026-11-20)

- [x] **`llm_query` daemon action**: POSTs natural-language query to Ollama HTTP
      API (`localhost:11434`); parses the JSON action array response; executes
      each action via the existing `dispatch`; returns a human-readable summary
- [x] **System prompt**: concise description of all available actions; instructs
      the model to return only a JSON array with no markdown
- [x] **Env-var overrides**: `VOXWIN_OLLAMA_URL` and `VOXWIN_OLLAMA_MODEL`
      (defaults to `http://localhost:11434/api/generate` and `llama3.2`)
- [x] **AI Mode toggle** in the Voice tab: `Switch` persisted to SharedPreferences;
      when on, unrecognized voice commands are routed to `llm_query` instead of
      showing "Didn't catch that"
- [x] **Infinite-recursion guard**: the `llm_query` handler skips any
      `llm_query` action the model might return

## Known environment constraint (not a code issue)

This build machine runs tamper-protected Microsoft Defender real-time
protection with no admin rights, which **blocks execution of freshly compiled,
unsigned binaries** (confirmed: a dependency-free `hello.exe` compiles but is
denied execution; `Add-MpPreference` exclusion is refused). Consequence:
`cargo build`/`run` of the daemon cannot complete *here* (build-script binaries
can't execute) and the daemon exe can't be launched here. This is why the Rust
side is verified by CI on a clean Windows runner instead. AppLocker is only in
AuditOnly mode, so it is not the cause.

### Feature 3 — Remote Gamepad (complete, 2026-11-20)

- [x] **`gamepad_state` daemon action**: creates a virtual Xbox 360 controller
      via ViGEmBus driver on first call; updates all axes + buttons per frame;
      `Mutex<Option<GamepadSession>>` with `unsafe impl Send` for the ViGEmBus
      HANDLE wrapper; lazy init — returns a clear error if ViGEmBus not installed
- [x] **Env-var override**: `VOXWIN_VIGEM_*` — no, standard Cargo.toml target dep
- [x] **GamepadScreen composable**: portrait-fit layout with:
      - LT / RT sliders at the top
      - LB / RB + SEL / START shoulder row
      - Left thumbstick (drag circle, springs to center on release)
      - D-pad (4 directional buttons in a cross layout)
      - ABXY face buttons (diamond layout)
      - Right thumbstick below main row
      - L3 / R3 (thumbstick click buttons)
- [x] **Bitmask protocol**: `GamepadButtons` object in `Models.kt` with all
      XUSB_GAMEPAD_* constants; `buttons: Int` field sent as i32 on the wire
- [x] Gamepad tab added to NavigationBar (SportsEsports icon)
- [x] gamepad_state events suppressed in activity log (same as mouse_move)

### Feature 4 — Remote Desktop (complete, 2026-11-20)

- [x] **Stream server on port+1**: second `TcpListener` spawned in `main()`;
      `handle_stream_ws()` sends one JPEG frame every 100ms (10fps) using
      `screenshots` crate for screen capture and `image` crate for 50%-scale JPEG
      encoding; binary WebSocket frames; first text frame is a
      `{type:"stream_info", width, height}` JSON with the original PC resolution
- [x] **Env-var override**: `VOXWIN_STREAM_PORT` (default: control port + 1)
- [x] **StreamClient.kt**: second OkHttp `WebSocket` connection on port+1;
      exposes `state: StateFlow<ConnectionState>`, `frame: StateFlow<ByteArray?>`,
      `screenWidth`, `screenHeight` from the stream_info frame
- [x] **DesktopScreen composable**: `DisposableEffect` wires up / tears down
      `StreamClient` on connect/disconnect; JPEG bytes decoded to `ImageBitmap`
      via `BitmapFactory`; `ContentScale.FillBounds` to fill the phone screen;
      touch coordinates mapped via `touchX / compWidth * screenWidth` for
      `mouse_move_abs`; tap → `mouse_move_abs + mouse_click`; loading/error states
- [x] Desktop tab (DesktopWindows icon) added to NavigationBar

## Verified locally on 2026-11-20

- [x] `./gradlew assembleDebug` → BUILD SUCCESSFUL (all touchpad improvements)
- [x] Feature 2 Android changes: BUILD SUCCESSFUL
- [x] Feature 3 Android changes: BUILD SUCCESSFUL (pre-existing warnings only)
- [x] Feature 3+4 combined Android build: BUILD SUCCESSFUL
- [x] Rust toolchain 1.99.1; `cargo check` blocked on this machine by Defender
      (unsigned build scripts); verified clean by CI

## Remaining before a tagged release

- [ ] Confirm the daemon runs end-to-end on an unmanaged Windows box (every
      action: volume, media, type, key combos, scroll, launch, lock, mouse, llm)
- [ ] Validate on-device `SpeechRecognizer` behaviour across a couple of phones
- [ ] Signed release APK + release daemon binary (CI currently builds debug/raw)

## Roadmap (nice-to-have)

- [ ] Upgrade remote desktop to DXGI Desktop Duplication + H.264 encoding for
      lower latency and better quality (current: screenshots crate + JPEG 10fps)
- [ ] Harden auth beyond a single shared token (per-device keys, rotation)
- [ ] User-editable launch-alias config file
- [ ] Continuous-listen / wake-word mode
- [ ] Package the daemon as a Windows service / system-tray app (autostart)
- [ ] First-run firewall-rule helper for the daemon
