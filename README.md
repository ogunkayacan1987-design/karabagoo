# SpeedGuard - Driving Assist App

Minimal driving-assist Android application that displays your current speed and the road speed limit. Warns you with a voice alert when you exceed the limit.

## Features

- **Current Speed** (left) — real-time GPS speed in km/h
- **Speed Limit** (right) — fetched from OpenStreetMap via Overpass API
- **Color Coding** — green when safe, red when exceeding
- **Voice Warning** — "Speed limit exceeded" spoken via TTS
- **Full Screen** — black background, huge bold numbers, zero distractions
- **Default Limit** — falls back to 50 km/h when no data is available

## Screenshots

The app runs in landscape mode with two large numbers side by side:

```
┌──────────────────────────────────────┐
│                                      │
│     87          │       110          │
│    km/h         │      LIMIT         │
│                                      │
└──────────────────────────────────────┘
```

Speed turns **red** when exceeding the limit.

## Tech Stack

- **Flutter** (Dart) — cross-platform UI
- **Geolocator** — GPS speed and coordinates
- **HTTP** — Overpass API requests for speed limits
- **Flutter TTS** — voice warnings
- **Wakelock Plus** — keeps screen on while driving

## Project Structure

```
├── lib/
│   └── main.dart              # Complete application code
├── pubspec.yaml               # Flutter dependencies
├── android/
│   ├── app/
│   │   ├── build.gradle       # App-level Gradle config
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── kotlin/.../MainActivity.kt
│   │       └── res/           # Icons, styles, launch background
│   ├── build.gradle           # Root Gradle config
│   ├── settings.gradle        # Gradle plugin management
│   └── gradle/                # Gradle wrapper
├── .github/workflows/
│   └── android.yml            # CI/CD: build + release APK
└── README.md
```

## Setup & Build

### Prerequisites

- Flutter SDK 3.24+ installed
- Android SDK with API 35
- Java 17

### Local Build

```bash
# Get dependencies
flutter pub get

# Build release APK
flutter build apk --release

# APK output at: build/app/outputs/flutter-apk/app-release.apk
```

### GitHub Actions (Automated)

The included workflow automatically:

1. Runs on every push to `main`/`master`
2. Installs Flutter and Java
3. Builds a release APK
4. Uploads APK as a downloadable artifact
5. Creates a GitHub Release when you push a version tag

#### Download APK from Actions

1. Go to **Actions** tab in your GitHub repo
2. Click the latest successful workflow run
3. Download `app-release` artifact

#### Create a Release with APK

```bash
git tag v1.0.0
git push origin v1.0.0
```

This triggers the workflow to create a GitHub Release with the APK attached.

### Install on Phone

1. Download `app-release.apk` from GitHub Actions or Releases
2. Transfer to your Android device
3. Enable "Install from unknown sources" if prompted
4. Open the APK and install
5. Grant location permission when asked
6. Mount your phone on the dashboard and drive

## Permissions

| Permission | Purpose |
|---|---|
| `ACCESS_FINE_LOCATION` | GPS speed and coordinates |
| `ACCESS_COARSE_LOCATION` | Fallback location |
| `INTERNET` | Fetch speed limits from OpenStreetMap |
| `WAKE_LOCK` | Keep screen on while driving |

## How It Works

1. GPS position is read every 1 second
2. Speed is calculated from GPS data (m/s → km/h)
3. Every 10 seconds, the current coordinates are sent to the Overpass API
4. The API returns the `maxspeed` tag from the nearest road
5. If no speed limit is found, defaults to 50 km/h
6. If current speed > limit, the number turns red and TTS says "Speed limit exceeded"

## Package Info

- **Package Name:** `com.speedguard.app`
- **Version:** 1.0.0
- **Min SDK:** 21 (Android 5.0)
- **Target SDK:** 35

## License

This project is provided as-is for personal use.
