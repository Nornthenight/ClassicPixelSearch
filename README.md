# ClassicPixelSearch

A native Android dock search widget for Google Pixel devices (Pixel Launcher) that restores the classic rounded Google Search bar with real-time OneSearch synchronization via Shizuku / Accessibility Service.

## Features

- **Pixel Launcher Design Matching**:
  - Pixel pill-shaped dock search bar with Google "G" logo, Mic, Lens, and Gemini / AI Mode buttons.
  - Pixel 8 / 9 dynamic color palette support (Default Dark/Light, Pastel, and Rich variants).
  - Micro-animations, touch ripples, and precise dock elevation margins matching Pixel Launcher specifications.
- **Smart Launcher Synchronization**:
  - **Shizuku Integration**: Monitors foreground state and synchronizes with Pixel Launcher's All Apps drawer search (`com.google.android.apps.nexuslauncher`).
  - **Accessibility Fallback (`LauncherStateService`)**: Ensures seamless sync even when Shizuku is not running.
- **Instant Launcher Launchers**:
  - Quick tap handlers for Google Search (`SearchTapActivity`), Assistant/Gemini, Google Voice Search, and Google Lens.
- **Customizable Preferences**:
  - Configuration activity for toggling AI Mode icon, pill styling, microphone visibility, and theme selection.

## Tech Stack

- **Language**: Kotlin 2.0+
- **Build System**: Gradle 8.9 (Kotlin DSL `build.gradle.kts`)
- **Min SDK**: Android 14 (API 34)
- **Target SDK**: Android 15 (API 35)
- **Libraries**:
  - AndroidX Core KTX, AppCompat, Material Components
  - Shizuku API (`rikka.shizuku:api`, `rikka.shizuku:provider`)

## Build & Install

```bash
# Clone the repository
git clone https://github.com/Nornthenight/ClassicPixelSearch.git
cd ClassicPixelSearch

# Build debug APK
./gradlew assembleDebug

# Install via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Setup & Permissions

1. Open the **Pixel Classic Search** settings app after installation.
2. (Optional but recommended) Authorize Shizuku for background app synchronization.
3. Enable the **Pixel Launcher Synchronizer** accessibility service if Shizuku is not used.
4. Add the **Pixel Classic Search** widget to your home screen or dock.

## License

MIT License. See [LICENSE](LICENSE) for details.
