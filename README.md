<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android"/>
  <img src="https://img.shields.io/badge/API-26+-brightgreen?style=for-the-badge" alt="API 26+"/>
  <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Compose"/>
  <img src="https://img.shields.io/github/license/tribixbite/Embeddy?style=for-the-badge" alt="License"/>
</p>

<p align="center">
  <img src="https://img.shields.io/github/v/release/tribixbite/Embeddy?style=flat-square&label=latest" alt="Release"/>
  <img src="https://img.shields.io/github/actions/workflow/status/tribixbite/Embeddy/release.yml?style=flat-square&label=build" alt="Build"/>
  <img src="https://img.shields.io/github/downloads/tribixbite/Embeddy/total?style=flat-square" alt="Downloads"/>
  <img src="https://img.shields.io/github/repo-size/tribixbite/Embeddy?style=flat-square" alt="Size"/>
</p>

# Embeddy

**The Swiss-army knife for embeddable media on Android.**

Convert videos and GIFs to platform-ready animated WebP, compress images to WebP/JPEG/PNG/AVIF, inspect Open Graph and social embed metadata, and upload files to anonymous hosts — all from one app with a clean Material 3 dark-mode UI.

## Download

<p align="center">
  <a href="https://github.com/tribixbite/Embeddy/releases/latest">
    <img src="https://img.shields.io/badge/GitHub-Latest_Release-181717?style=for-the-badge&logo=github" alt="Download"/>
  </a>
</p>

Every push to `main` triggers a CI build that produces split-ABI APKs:

| ABI | Best for |
|-----|----------|
| `arm64-v8a` | Modern phones (Pixel, Samsung Galaxy S/A series, OnePlus) |
| `armeabi-v7a` | Older 32-bit ARM devices |
| `x86_64` | Emulators, Chromebooks |
| `universal` | Any device (larger download) |

## Features

### Convert — Animated WebP from any video/GIF

- **Adaptive quality** algorithm — starts at target quality, steps down until file fits the size limit
- **Platform presets** with tuned parameters:
  - **Discord** — 10 MB / 720p / 12 fps
  - **Telegram** — 256 KB / 512px / 30 fps (sticker-ready)
  - **Slack** — 5 MB / 640px / 12 fps
  - **Custom** — full manual control
- Lanczos downscaling, optional text sharpening (`unsharp` filter)
- Real-time progress with quality-step feedback
- Share/Save output directly from the result card
- Accepts shared media via Android intent filters

### Inspect — Social embed metadata viewer

- Fetches and parses Open Graph, Twitter Card, and general `<meta>` tags
- Discord/Slack-style live embed preview card
- Expandable tag sections with one-tap copy
- Extracts favicon, canonical URL, charset, theme-color, robots, generator
- Powered by Jsoup for robust HTML parsing

### Upload — Anonymous file hosting

- Upload to **0x0.st** (512 MB) or **catbox.moe** (200 MB)
- **EXIF stripping** — removes GPS, camera info, artist, serial numbers before upload
- Determinate progress bar with chunked streaming (no full-file memory buffering)
- Copy URL or share result instantly

### Squoosh — Still image compression

- Output formats: **WebP**, **JPEG**, **PNG**, **AVIF**
- AVIF encoding via [avif-coder](https://github.com/awxkee/avif-coder) (libaom, speed 6 — Squoosh.app equivalent)
- Quality slider (1–100) for lossy formats
- Lossless mode for WebP and AVIF
- Max dimension scaling with efficient `inSampleSize` subsampling
- Before/after size comparison with savings percentage
- Image preview of compressed output

## Architecture

```
app.embeddy
├── conversion/          # FFmpeg-kit animated WebP pipeline
│   ├── ConversionEngine # Adaptive quality loop with callbackFlow
│   ├── ConversionConfig # Mutable settings from presets
│   └── Preset           # Discord, Telegram, Slack, Custom
├── inspect/
│   └── MetadataEngine   # Jsoup-based OG/Twitter/meta parser
├── squoosh/
│   └── SquooshEngine    # Bitmap.compress + HeifCoder AVIF
├── upload/
│   └── UploadEngine     # Multipart POST with ProgressOutputStream
├── viewmodel/           # AndroidViewModel per tab
├── navigation/
│   ├── EmbeddyTab       # Tab enum with Material icons
│   └── AppScaffold      # Scaffold + NavigationBar + AnimatedContent
└── ui/
    ├── screens/         # ConvertScreen, InspectScreen, UploadScreen, SquooshScreen
    ├── components/      # MediaPicker, SettingsPanel, ConversionProgress, OutputPreview
    └── theme/           # Material 3 dark theme
```

## Tech Stack

| Component | Library |
|-----------|---------|
| UI | Jetpack Compose + Material 3 |
| Video → WebP | [FFmpeg-kit](https://github.com/AdrianAndroid/ffmpeg-kit) (libwebp_anim) |
| AVIF encoding | [avif-coder](https://github.com/awxkee/avif-coder) (libaom via JNI) |
| Image loading | [Coil](https://coil-kt.github.io/coil/) (compose, gif, video) |
| HTML parsing | [Jsoup](https://jsoup.org/) |
| EXIF handling | AndroidX ExifInterface |
| Navigation | Tab-based with `AnimatedContent` |
| Async | Kotlin Coroutines + `StateFlow` |

## Building

### Prerequisites

- JDK 17+
- Android SDK with `compileSdk 35`

### Standard build

```bash
./gradlew assembleDebug
```

### On Termux (ARM64 Android)

A helper script handles the aapt2 override needed for on-device builds:

```bash
bash build-and-install.sh              # build debug
bash build-and-install.sh --install    # build + install via adb
bash build-and-install.sh --release    # build release
```

### Split APKs

The build produces per-ABI APKs (`arm64-v8a`, `armeabi-v7a`, `x86_64`) plus a universal APK. Configured in `app/build.gradle.kts`:

```kotlin
splits {
    abi {
        isEnable = true
        include("arm64-v8a", "armeabi-v7a", "x86_64")
        isUniversalApk = true
    }
}
```

## CI/CD

Every push to `main` triggers the GitHub Actions workflow:

1. Build release APKs (all ABIs)
2. Rename with version: `Embeddy-v{major}.{minor}.{build}-{abi}.apk`
3. Upload as build artifacts (30-day retention)
4. Create a GitHub Release tagged `v{major}.{minor}.{build}` with:
   - Auto-generated changelog from commit messages
   - All APK variants attached
   - Marked as `latest`

Version is derived from `version.properties` (major/minor) + `github.run_number` (patch).

## License

```
GPL-3.0 License — see LICENSE file for details.
```
