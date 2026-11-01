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

**A quick video editor and embed-media toolkit for Android and the web.**

Trim, join, speed up, crop and convert clips; turn photos into an animation;
and get images and video into the size and format that chat apps, forums and
social sites accept. Everything runs on the device — nothing is uploaded unless
you use the Upload tool. The same tools run in the browser at
[embeddy.link](https://embeddy.link/).

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

### Edit — trim, join and re-time video

- Trim to a range, or cut several segments and stitch them together
- Join separate videos in any order, each with its own trim
- Speed 0.25×–4× (audio keeps its pitch), rotate, flip, crop and reverse
- Per-segment volume, mute, or extract the audio track as M4A
- Grab a still frame and send it to Squoosh
- Turn a set of photos into an animation or a video
- Accepts shared media via Android intent filters (single or multiple files)

### Convert — MP4, WebP or GIF under a size limit

- Export MP4, the original container, animated WebP or GIF
- **Adaptive quality** for WebP/GIF — starts at target quality, steps down until the file fits;
  for MP4 the bitrate is computed from the size target
- **Platform presets**:
  - **Discord** — 10 MB / 720p / 12 fps
  - **Telegram** — 256 KB / 512px / 30 fps (sticker-ready)
  - **Slack** — 5 MB / 640px / 12 fps
  - **Custom** — full manual control
- **Animated WebP input** — frames are decoded in-app by splitting the RIFF
  container, because the bundled FFmpeg cannot demux the format
- Lanczos downscaling, optional text sharpening (`unsharp` filter), denoise, dithering
- Exact output dimensions with centre-crop, or scale to a maximum
- 3-second preview clip before committing to a full encode
- Location and other metadata removed from exported video
- Share/Save output directly from the result card

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
├── conversion/   # ConversionEngine (FFmpeg-kit, adaptive quality), EditSpec, FfmpegCommands,
│                 # ConcatScript (joins), ImageSequenceRenderer, WebP RIFF frame splitting
├── inspect/      # MetadataEngine — Jsoup OG/Twitter parser + local EXIF/codec probe
├── squoosh/      # SquooshEngine — Bitmap.compress + HeifCoder AVIF
├── upload/       # UploadEngine — streamed multipart POST with progress
├── util/         # URI staging, settings DataStore, size estimation, temp cleanup worker
├── viewmodel/    # ConvertController + one ViewModel per tab
├── navigation/   # EmbeddyTab, AppScaffold
└── ui/
    ├── screens/     # Convert, Inspect, Squoosh, Upload
    ├── components/  # VideoTrimPlayer, CropOverlay, EditTools, MultiSourceCards, FrameGrabSheet, ...
    └── theme/       # Material 3 dark theme

site/     # Astro + Svelte browser versions of the four tools (Mediabunny, jSquash WASM)
worker/   # Cloudflare Worker (Hono) — Inspect fetch proxy with SSRF guard, upload relay
```

## Tech Stack

| Component | Library |
|-----------|---------|
| UI | Jetpack Compose + Material 3 |
| Video editing / encoding | FFmpeg-kit 6.1.1, 16 KB-page build (`com.moizhassan.ffmpeg:ffmpeg-kit-16kb`) |
| AVIF encoding | [avif-coder](https://github.com/awxkee/avif-coder) (libaom via JNI) |
| Image loading | [Coil](https://coil-kt.github.io/coil/) (compose, gif, video) |
| Video preview | AndroidX Media3 ExoPlayer |
| Settings | AndroidX DataStore |
| Temp cleanup | AndroidX WorkManager |
| HTML parsing | [Jsoup](https://jsoup.org/) |
| EXIF handling | AndroidX ExifInterface |
| Navigation | Tab-based with `AnimatedContent` |
| Async | Kotlin Coroutines + `StateFlow` |

## Building

### Prerequisites

- JDK 17+
- Android SDK with `compileSdk 36`

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

Three workflows run on push to `main`:

**Tests** (`test.yml`) — gates everything else. Runs the Android unit suite, the
Worker's SSRF tests plus typecheck, and the site's unit tests plus `astro check`.
Also runs on pull requests.

**Deploy Site** (`pages.yml`) — runs the site tests, then builds and deploys to
GitHub Pages. A failing suite blocks the deploy.

**Build & Release** (`release.yml`):

1. Run the Android unit tests — a failure blocks the release
2. Pin the resolved version into `version.properties` and commit it, with a
   skip-CI marker so the push does not re-trigger the workflow
3. Build release APKs for the three configured ABIs plus universal, and a Play
   App Bundle (`.aab`, uploaded as the `embeddy-v<ver>-play-bundle` artifact)
4. Tag the version-pinned commit and publish a GitHub Release with grouped
   release notes, a download table with sizes and SHA-256 prefixes, and
   `SHA256SUMS.txt`

Version is `version.properties` (major/minor) + `github.run_number` (patch).
Step 2 matters for anyone rebuilding from a tag — without it the version exists
only in the CI environment and a tag build produces a stale version number.

### Testing

```sh
./gradlew :app:testDebugUnitTest     # Android
cd worker && bun test                # Worker (SSRF)
cd site   && bun test src            # Site (WebP parsing, upload retry)
```

## F-Droid

Store metadata lives in `fastlane/metadata/android/en-US/`. A draft build recipe
and a submission guide — including the dependency-provenance questions a
reviewer will raise — are in [`fdroid/`](fdroid/README.md).

Build a single all-ABI APK the way F-Droid does:

```sh
./gradlew assembleRelease -PsingleApk
```

## License

```
GPL-3.0-only — see LICENSE file for details.
```
