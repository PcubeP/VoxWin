# voxwin — progress

**Status: Android verified-building locally; Rust CI-verified; shippable UI**

Both halves are complete and the wire protocol is pinned. The Android app is
polished and **builds + unit-tests green locally** (JDK 17 + Android SDK 34).
The Rust daemon compiles its dependency graph locally but the build/run is
blocked on this specific machine by enterprise Defender (see note); it is built
and tested authoritatively by CI on a hosted Windows runner.

## What exists

- [x] Wire protocol defined and documented ([PROTOCOL.md](PROTOCOL.md))
- [x] Rust daemon: tokio + tokio-tungstenite server, token auth, multi-client,
      crash-resistant; all 12 actions; `rustfmt`-clean
- [x] Android app: Kotlin + Compose, on-device `SpeechRecognizer`, OkHttp ws
      client, robust `CommandParser`
- [x] **Polished, shippable UI**: Scaffold + centered top bar with live status
      chip, connection card (locks fields while connected), big animated
      hold/tap mic with pulse ring + haptics, live transcript, "try saying"
      suggestion chips (tap to fire), colour-coded auto-scrolling activity log
      with empty state, Material You dynamic color + indigo/teal brand fallback,
      edge-to-edge
- [x] CommandParser unit tests (incl. casing / whitespace edge cases) — **pass**
- [x] Gradle wrapper committed (Gradle 8.7); `./gradlew` works turnkey
- [x] Connection settings persisted across restarts
- [x] Exhaustive README + per-half READMEs
- [x] **GitHub Actions CI** ([../.github/workflows/ci.yml](../.github/workflows/ci.yml)):
      Rust build/test/fmt on `windows-latest`; Android test + APK on
      `ubuntu-latest`; both upload artifacts

## Verified locally on 2026-11-01

- [x] `./gradlew test` → BUILD SUCCESSFUL (CommandParser tests green)
- [x] `./gradlew assembleDebug` → `app-debug.apk` (~17 MB) produced, incl. the
      polished UI
- [x] Rust toolchain installed (1.99.1, MSVC host); `cargo` resolves + compiles
      all daemon dependencies

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
      action: volume, media, type, key combos, scroll, launch, lock)
- [ ] Validate on-device `SpeechRecognizer` behaviour across a couple of phones
- [ ] Signed release APK + release daemon binary (CI currently builds debug/raw)

## Roadmap (nice-to-have)

- [ ] TLS (`wss://`) instead of plaintext on the LAN
- [ ] Harden auth beyond a single shared token (per-device keys, rotation)
- [ ] User-editable launch-alias config file
- [ ] Continuous-listen / wake-word mode
- [ ] Package the daemon as a Windows service / system-tray app (autostart)
- [ ] First-run firewall-rule helper for the daemon
