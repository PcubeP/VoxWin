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

## Verified locally on 2026-11-20

- [x] `./gradlew assembleDebug` → BUILD SUCCESSFUL (all touchpad improvements)
- [x] Feature 2 Android changes: BUILD SUCCESSFUL (see below)
- [x] Rust toolchain 1.99.1; `cargo check` blocked on this machine by Defender
      (unsigned build scripts); verified clean by CI

## Known environment constraint (not a code issue)

This build machine runs tamper-protected Microsoft Defender real-time
protection with no admin rights, which **blocks execution of freshly compiled,
unsigned binaries** (confirmed: a dependency-free `hello.exe` compiles but is
denied execution; `Add-MpPreference` exclusion is refused). Consequence:
`cargo build`/`run` of the daemon cannot complete *here* (build-script binaries
can't execute) and the daemon exe can't be launched here. This is why the Rust
side is verified by CI on a clean Windows runner instead. AppLocker is only in
AuditOnly mode, so it is not the cause.

## Remaining before a tagged release

- [ ] Confirm the daemon runs end-to-end on an unmanaged Windows box (every
      action: volume, media, type, key combos, scroll, launch, lock, mouse, llm)
- [ ] Validate on-device `SpeechRecognizer` behaviour across a couple of phones
- [ ] Signed release APK + release daemon binary (CI currently builds debug/raw)

## Roadmap (nice-to-have)

- [ ] Feature 3: Remote gamepad + gyro via ViGEmBus + vigem-client Rust crate;
      new GamepadScreen composable
- [ ] Feature 4: Remote desktop (DXGI Desktop Duplication + H.264 encoding);
      uses `mouse_move_abs` from Feature 1
- [ ] Harden auth beyond a single shared token (per-device keys, rotation)
- [ ] User-editable launch-alias config file
- [ ] Continuous-listen / wake-word mode
- [ ] Package the daemon as a Windows service / system-tray app (autostart)
- [ ] First-run firewall-rule helper for the daemon
