# ClassicPixelSearch

A minimalist native Android search dock widget for Google Pixel devices (Pixel Launcher) featuring the classic concentric double-frame search bar, real-time Material You / Monet dynamic color synchronization, native **Default Search Engine** integration, and an optional **Shizuku / ADB** fallback.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2012%2B-green.svg)](https://developer.android.com)
[![Android CI](https://github.com/Nornthenight/ClassicPixelSearch/actions/workflows/ci.yml/badge.svg)](https://github.com/Nornthenight/ClassicPixelSearch/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Nornthenight/ClassicPixelSearch?include_prereleases&label=release)](https://github.com/Nornthenight/ClassicPixelSearch/releases)

---

## Key Features

- **Minimalist Native Search Integration**:
  - **Primary — Default Search Engine**: Uses Android's `RoleManager` (`android.app.role.SEARCH_ENGINE`) and Pixel Launcher's search engine settings so the widget integrates into the Pixel Launcher dock natively without root or Shizuku.
  - **Optional Fallback — Shizuku / ADB**: On regions or builds without the native Default Search Engine picker, optionally grant `WRITE_SECURE_SETTINGS` via Shizuku or ADB to set `selected_search_engine` directly.
  - **Zero Dock Flickering**: `LauncherEnginePolicy` and passive `LauncherStateService` keep the dock stable and synchronize widget colors immediately on wallpaper, palette, or day/night theme changes.
- **Pixel Launcher Aesthetic & Live Preview**:
  - Classic concentric double-frame pill with Google "G" logo, Microphone, Middle (Gemini / Lens), and AI Circle buttons.
  - Surface styles: **Deep Ring**, **Pastel**, **Bold Accent**, and **AMOLED Black**.
  - Icon styles: **Adaptive Emerald**, **Dynamic Monet**, and **Classic Color**.
  - Pixel-style haptic feedback (`CLOCK_TICK` or `CONTEXT_CLICK`).
- **Fine-Grained Geometry & Alpha Controls**:
  - Independent sliders for **Opacity (`30–100%`)**, **Outer Ring Alpha (`0–100%`)**, **Ring Gap (`2–12 dp`)**, and **Corner Radius (`12–31 dp`)**.
- **Custom Right-Zone Button Remapping**:
  - Assign custom actions to the Microphone, Middle, and AI Circle buttons: Voice Search, Google Gemini, Google Lens, Google AI Mode (`udm=50`), ChatGPT, Perplexity, QR Scanner, Brave, Firefox, Cromite, or **any installed application**.

---

## Tech Stack

- **Language**: Kotlin 2.0+
- **Build System**: Gradle (Kotlin DSL `build.gradle.kts`)
- **Min SDK**: Android 12 (API 31)
- **Target SDK**: Android 15 (API 35) — full edge-to-edge & display cutout insets
- **Core Dependencies**:
  - AndroidX Activity KTX, Core KTX, AppCompat, Material Components
  - Shizuku API (`rikka.shizuku:api`, `rikka.shizuku:provider`) for optional fallback setup

---

## Installation & Setup

### 1. Download & Install
Download the latest APK from [GitHub Releases](https://github.com/Nornthenight/ClassicPixelSearch/releases) (or grab a build artifact from [GitHub Actions](https://github.com/Nornthenight/ClassicPixelSearch/actions)) and install it:

```bash
adb install -r ClassicPixelSearch.apk
```

### 2. Configure Dock Integration
1. Open **Pixel Search** from your app drawer.
2. Under **Default Search Engine**, tap **Set as Default Search Engine** to select Pixel Search in the system / Pixel Launcher picker.
3. **Optional Fallback (Shizuku / ADB)**: If your device or region does not display the native picker, scroll to **Optional Fallback (Shizuku / ADB)** to grant `WRITE_SECURE_SETTINGS` via Shizuku (or `adb shell pm grant com.pixel.classicsearch android.permission.WRITE_SECURE_SETTINGS`) and enable **Replace Native Pixel Launcher Dock**.
4. **Standalone Widget**: Alternatively, tap **Pin as Standalone Widget** (or add via Home screen → *Widgets* → *Pixel Search*).

---

## Branches & CI

- **`main`**: Stable releases and tagged builds (`v*`).
- **`dev`**: Active development branch.
- **GitHub Actions CI**: Every push and pull request targeting `main` or `dev` runs `./gradlew testDebugUnitTest`, `./gradlew assembleDebug`, and `./gradlew assembleRelease` (`.github/workflows/ci.yml`), uploading debug and release APKs as workflow artifacts.

---

## Building from Source

```bash
git clone https://github.com/Nornthenight/ClassicPixelSearch.git
cd ClassicPixelSearch

# Run unit tests
./gradlew testDebugUnitTest

# Build debug / release APKs
./gradlew assembleDebug
./gradlew assembleRelease
```

---

## License

Distributed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
