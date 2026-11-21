# Dashcam Explorer

An Android app for browsing the memory card of a Qubo dual-camera dashcam: watch the front and rear cameras together in sync, export a combined video, build a timelapse of a day, and read a summary of the camera's health log.

Files come from a card in a USB reader, any folder on the device, or the camera's own Wi-Fi. Everything is processed on the device; the only network traffic is to the camera itself.

## Features

- **Explorer** — the card as a collapsible tree with sizes, file counts, real dates and a name filter.
- **Viewer** — front and rear clips played together and kept in sync, with seek, speed, previous/next and auto-play. Photos are shown as pairs.
- **Camera over Wi-Fi** — clips start playing about 3 seconds after opening, while they are still downloading.
- **Combined export** — one video with the front camera on top and the rear below, saved to `Movies/DashcamExplorer`.
- **Day timelapse** — a video built from a day's timelapse photos at 8, 15 or 30 frames per second.
- **Health summary** — restarts per day and setting changes from the camera's log. The Wi-Fi password stored in that log is never shown.

## Project structure

```
├── app/                          the application module
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/dev/dashcam/explorer/
│       │   │   ├── domain/       file names, front/rear pairing, health-log parser
│       │   │   ├── data/         folder and Wi-Fi camera sources, tree cache
│       │   │   ├── media/        synced player, play-while-downloading, export, timelapse
│       │   │   └── ui/           activity, view model, Compose screens
│       │   └── res/
│       └── test/                 JVM unit tests
├── docs/                         design, test results, performance notes
├── gradle/
│   ├── libs.versions.toml        plugin and library versions
│   └── wrapper/
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Build

Requirements: JDK 17 or newer and the Android SDK with platform 37.2. Either open the folder in Android Studio, or create `local.properties` with `sdk.dir=<path to your Android SDK>` and use the Gradle wrapper:

```bash
./gradlew testDebugUnitTest    # unit tests
./gradlew assembleDebug        # debuggable build
./gradlew assembleRelease      # optimised build (R8), about 4 MB
```

Install on a device with USB debugging on:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the local debug key so that it installs without a keystore. Give it a real signing configuration before distributing it.

## Technology

Kotlin, Jetpack Compose (Material 3), Media3 (ExoPlayer and Transformer), MediaCodec/MediaMuxer, the Storage Access Framework and Coil. Minimum Android 10 (API 29), target API 36.

## Documentation

- [Design document](docs/DESIGN.md) — the card's layout, architecture, screens and how each feature works.
- [Test results](docs/TEST-RESULTS.md) — unit tests and the regression runs on a tablet.
- [Performance](docs/PERFORMANCE.md) — the performance pass and its measurements.

## Status

Version 0.1.0. Tested on one device (Xiaomi Pad 6, Android 14) with one camera model. This is an independent project and is not affiliated with Qubo.

## License

Apache License 2.0. See [LICENSE](LICENSE).
