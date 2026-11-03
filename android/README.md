# voxwin — Android client

Native Kotlin + Jetpack Compose app. Listens with the on-device
`SpeechRecognizer`, maps spoken phrases to commands, and sends them as JSON over
a WebSocket to the voxwin Rust daemon on a Windows PC on the same LAN.

## Module layout

```
android/
  settings.gradle.kts
  build.gradle.kts            # root, plugin versions
  gradle.properties
  gradlew / gradlew.bat       # Gradle wrapper launchers (committed)
  gradle/wrapper/             # gradle-wrapper.jar + .properties (Gradle 8.7, committed)
  app/
    build.gradle.kts
    proguard-rules.pro
    src/main/AndroidManifest.xml
    src/main/res/...           # theme, strings, launcher icon, network config
    src/main/java/dev/voxwin/app/
      MainActivity.kt          # Compose UI
      SpeechController.kt      # SpeechRecognizer wrapper
      CommandParser.kt         # pure phrase -> Command mapping (unit-tested)
      VoxwinClient.kt          # OkHttp WebSocket client
      Models.kt                # serializable wire models
    src/test/java/dev/voxwin/app/
      CommandParserTest.kt     # JUnit tests
```

## Building

Needs **JDK 17** (AGP 8.5.2 requirement) and an Android SDK with **API 34**. The
Gradle wrapper (including `gradle-wrapper.jar`, pinned to Gradle 8.7) is
committed, so no separate Gradle install is needed:

```
cd android
# One-time: point the build at your SDK (git-ignored, machine-specific):
#   echo "sdk.dir=C:\\\\path\\\\to\\\\Android\\\\Sdk" > local.properties

./gradlew test             # run CommandParserTest (no device needed)
./gradlew assembleDebug    # build the debug APK
./gradlew installDebug     # build + install onto a connected device (adb)
```

On Windows `cmd`/PowerShell use `gradlew.bat`. Or open `android/` in Android
Studio and Run.

Connection settings (host / port / token) are entered in the UI and persisted
across restarts via `SharedPreferences`.

## Wire protocol (ws://, default port 8787)

Client -> daemon:
```json
{ "type": "command", "token": "voxwin-dev", "action": "volume_up", "args": {} }
```

Daemon -> client: `hello` on connect, then `ack` / `error` per command.

### Voice -> action
| say | action |
|-----|--------|
| volume up / louder | `volume_up` |
| volume down / quieter | `volume_down` |
| mute / unmute | `mute` |
| play / pause / play pause | `media_play_pause` |
| next / next track / skip | `media_next` |
| previous / back / last track | `media_prev` |
| open/launch/start X | `launch {app:X}` |
| type TEXT… | `type_text {text:TEXT}` |
| press COMBO | `key {combo:…}` (`control`->`ctrl`, spaces->`+`) |
| scroll up / scroll down | `scroll {direction, amount:3}` |
| lock / lock (the) computer | `lock` |
| anything else | ignored, UI shows "didn't catch that" |

## Assumed library versions
- Android Gradle Plugin 8.5.2, Gradle 8.7
- Kotlin 1.9.24, Compose compiler ext 1.5.14, Compose BOM 2024.09.00
- activity-compose 1.9.2, core-ktx 1.13.1, lifecycle-runtime-ktx 2.8.5
- OkHttp 4.12.0, kotlinx-serialization-json 1.6.3, kotlinx-coroutines-android 1.8.1
- JUnit 4.13.2
