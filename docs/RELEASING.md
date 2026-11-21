# Releasing voxwin

This document explains how the downloadable binaries are published and the
one-time setup required for release signing.

## How releases are published (the chosen method)

> **Downloadable releases are published by pushing a git tag `vX.Y.Z`.**
> There is no other automatic path — commits to `master` only run CI (tests +
> debug APK as a short-lived artifact), they do **not** create a public download.

When you push a tag matching `v*`, the [`Release` workflow](../.github/workflows/release.yml)
builds both halves and attaches them to a GitHub Release:

| Asset | Platform | Build |
|---|---|---|
| `voxwin.apk` | Android phone | release-signed `assembleRelease` |
| `voxwin-daemon.exe` | Windows PC | `cargo build --release` |

The two files then appear as download links on the repository's
**Releases** page (`https://github.com/PcubeP/VoxWin/releases`).

You can also trigger it manually from the **Actions → Release → Run workflow**
button, supplying a tag name.

## One-time setup: Android release signing

A release-signed APK is required so the app is installable. Signing needs a
keystore, which must **never** be committed to the repo — it lives only as
GitHub Actions secrets.

### 1. Generate a keystore (once)

```bash
keytool -genkeypair -v \
  -keystore voxwin-release.jks \
  -alias voxwin \
  -keyalg RSA -keysize 2048 -validity 10000
```

`keytool` ships with the JDK. It will prompt for a keystore password, a key
password, and your name/org (the latter can be anything). **Keep
`voxwin-release.jks` and both passwords somewhere safe** — if you lose them you
cannot ship an update that upgrades the same app installation.

### 2. Base64-encode the keystore

GitHub secrets hold text, so encode the binary keystore to a single line:

```bash
# Linux / macOS / Git Bash
base64 -w0 voxwin-release.jks > voxwin-release.jks.b64
```

```powershell
# PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("voxwin-release.jks")) > voxwin-release.jks.b64
```

### 3. Add the four repository secrets

Repo → **Settings → Secrets and variables → Actions → New repository secret**.
Create exactly these four:

| Secret name | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the entire contents of `voxwin-release.jks.b64` |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password from step 1 |
| `ANDROID_KEY_ALIAS` | `voxwin` (the `-alias` from step 1) |
| `ANDROID_KEY_PASSWORD` | the key password from step 1 |

> Do not commit `voxwin-release.jks`, the `.b64` file, or the passwords. Delete
> the local `.b64` file after pasting it. (`*.jks` / `*.keystore` should stay out
> of git.)

### How the build consumes them

[`release.yml`](../.github/workflows/release.yml) decodes
`ANDROID_KEYSTORE_BASE64` back to a `.jks` file in the runner temp dir, then
exports the signing values as environment variables for the Gradle build:

```
VOXWIN_KEYSTORE_FILE      -> path to the decoded keystore
VOXWIN_KEYSTORE_PASSWORD  -> ANDROID_KEYSTORE_PASSWORD
VOXWIN_KEY_ALIAS          -> ANDROID_KEY_ALIAS
VOXWIN_KEY_PASSWORD       -> ANDROID_KEY_PASSWORD
```

[`android/app/build.gradle.kts`](../android/app/build.gradle.kts) reads those
env vars in its `signingConfigs { release { ... } }` block and attaches the
config to the `release` build type only when `VOXWIN_KEYSTORE_FILE` is set. If
the secrets are missing, the `Decode keystore` step fails fast with a clear
error pointing back to this document.

## Cutting a release

Once the four secrets exist:

```bash
git tag v0.2.0
git push origin v0.2.0
```

Watch **Actions → Release**. When it finishes, the release at
`https://github.com/PcubeP/VoxWin/releases/tag/v0.2.0` will have both
`voxwin.apk` and `voxwin-daemon.exe` attached. Release notes are generated
automatically from the commits since the previous tag.

To match versions, bump `versionName`/`versionCode` in
[`android/app/build.gradle.kts`](../android/app/build.gradle.kts) and `version`
in [`windows-daemon/Cargo.toml`](../windows-daemon/Cargo.toml) before tagging.

## Optional: local signed build

To build a signed release APK locally without env vars, add to
`~/.gradle/gradle.properties` (never the repo):

```properties
voxwin.keystore.file=/absolute/path/to/voxwin-release.jks
voxwin.keystore.password=...
voxwin.key.alias=voxwin
voxwin.key.password=...
```

then `./gradlew assembleRelease` from `android/`.
