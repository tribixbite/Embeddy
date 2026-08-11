# Embeddy — Task Tracker

## Phase 1: Critical Bug Fixes

- [x] **1.1** UploadViewModel: store upload Job, enable cancellation + cancel method
- [x] **1.2** SquooshViewModel: cancel prior job before starting new compression
- [x] **1.3** Extract shared FileInfoUtils (queryFileName/queryFileSize/queryFileInfo DRY)
- [x] **1.4** OutputPreview: `remember` ImageLoader instead of recreating per recompose
- [x] **1.5** UploadEngine: pre-validate file size vs host limits before upload
- [x] **1.6** InspectViewModel: store fetch Job for cancellation

## Phase 2: New Features

- [x] **2.1** Exact crop dimensions: width x height fields in both Squoosh and Convert settings
- [x] **2.2** SquooshEngine: center-crop for exact dimensions (scale-to-fill + crop)
- [x] **2.3** ConversionEngine: exact dimensions via FFmpeg scale+crop filter chain
- [x] **2.4** Advanced encoding flags in Convert settings panel:
  - Denoise (hqdn3d with 0-10 strength slider)
  - Color Space selector (Auto/YUV420/YUV444/RGB)
  - Dithering mode (None/Bayer/Floyd-Steinberg/Sierra)
  - Keyframe interval (auto or custom frame count)
  - Compression level slider (0-6, replaces two-pass toggle)
- [x] **2.5** Inspect from file URIs: local media inspection via MediaMetadataRetriever + ExifInterface
  - Video: width, height, rotation, frame count, duration, bitrate, codec, track info
  - Image: EXIF camera, exposure, GPS, dates, color space, artist, copyright
  - File: name, MIME type, size
- [x] **2.6** Info icon (i) in Convert tab ReadyCard → navigates to Inspect with file metadata
- [x] **2.7** Squoosh before/after slider preview (drag divider reveals original vs compressed)
- [x] **2.8** Inspect supports picking files directly (Inspect File button alongside Fetch URL)

## Phase 3: Code Quality & Polish

- [x] **3.1** Move all hardcoded strings to strings.xml
  - ConvertScreen: "Change file", "Try again"
  - SquooshScreen: "Compression failed", "Try again", "Change image", "Compress another"
  - UploadScreen: "Try again", "Change file", "Upload another"
  - VideoTrimPlayer: "Pause", "Play", "Trim"
  - InspectScreen: hint text, "Failed to fetch"
- [x] **3.2** ConversionEngine.probeInput: extract bitrate, rotation, mimeType, frameCount
- [x] **3.3** MetadataEngine: cancellable via Job in InspectViewModel

## Phase 4: Compromises Resolved

- [x] **4.1** Replace two-pass toggle with compression_level slider (0-6)
  - libwebp_anim doesn't support FFmpeg `-pass`; exposed `compressionLevel` slider instead
- [x] **4.2** BPP-based size estimation in VideoTrimPlayer
  - Formula: `(width * height * totalFrames * bpp) / 8` where bpp maps quality 0-100 → 0.05-0.30
  - Falls back to proportional heuristic when dimensions unavailable
- [x] **4.3** Pixel-perfect before/after slider via `drawWithContent` + `clipRect`
  - Replaced Box offset masking approach; clips at draw level for all aspect ratios
- [x] **4.4** Preferences DataStore for persisting user settings across sessions
  - SettingsRepository saves/restores SquooshConfig and ConversionConfig
  - ViewModels auto-restore on init, persist on every setting change
  - Trim values excluded from persistence (per-file, not user preferences)

## Phase 5: Full Audit (2026-08-10)

Audit of the Android app, the Astro site and the Cloudflare Worker.

### Android — correctness
- [x] **5.1** ConversionEngine ran zero encode attempts when `startQuality` was below
  the preset's `minQuality` (slider allows 30, Discord's floor is 50), so every
  conversion failed with "no output from FFmpeg". Added `effectiveMinQuality`.
- [x] **5.2** FFmpeg `-ss`/`-to`/`trim` timestamps used the default locale, emitting
  `1,500` on comma-decimal devices. Pinned to `Locale.US`.
- [x] **5.3** `cancelPreview()` restored from SavedStateHandle, which can be empty and
  left the UI stuck on the spinner. `Previewing` now carries `previousReady`.
- [x] **5.4** Preview failures silently reverted to an unchanged Ready card; they now
  surface the reason via `Ready.notice`.
- [x] **5.5** `startPreview()` now merges overlapping segments like `startConversion()`.
- [x] **5.6** SquooshEngine read the original size from `InputStream.available()`
  (an estimate), skewing `savingsPercent`. Prefer the provider's SIZE column.
- [x] **5.7** `centerCrop` could throw when truncation left the scaled bitmap a pixel
  short of the crop rect.

### Android — security & resources
- [x] **5.8** `DISPLAY_NAME` is provider-controlled and flowed unsanitized into
  `File(dir, name)` and multipart headers. Added `FileInfoUtils.sanitizeFileName`.
- [x] **5.9** `probeKeyframes` walked every packet in the file, hanging Inspect on
  long videos. Samples the first 30s via `-read_intervals`.
- [x] **5.10** CleanupWorker missed `upload_temp` and `inspect_temp`.
- [x] **5.11** SettingsRepository dropped `minQuality`, `qualityStep` and exact dims.
- [x] **5.12** No `launchMode`, so `onNewIntent` never fired and a share while running
  started a second activity. Set `singleTask`; a share now also surfaces Convert.
- [x] **5.13** `splits.abi.include()` without `reset()` built 9 APKs instead of 4;
  five had no FFmpeg `.so` files and would crash on first conversion.

### Web — Convert pipeline
- [x] **5.14** Adaptive target-size loop dereferenced a null blob below quality 5.
- [x] **5.15** Video frame 0 was blank — `seekTo(0)` short-circuits, so `drawImage`
  ran at readyState 1. Verified against a pre-fix build (mean luma 0.0 → 123.7).
- [x] **5.16** Large-video → GIF subsampled twice (half speed, half the frames).
- [x] **5.17** `decodeGif` had no memory cap (a long 1080p GIF is ~8 GB of RGBA) and
  blocked the main thread so progress never painted.
- [x] **5.18** Implemented GIF disposal method 3 (restore to previous).
- [x] **5.19** `SourceInfo.frameCapped` replaces the `frameCount >= 1500` guess.
- [x] **5.20** Target-size field hidden in the modes that ignore it.
- [x] **5.21** StreamingWebPEncoder leaked its Worker on init/push failure.
- [x] **5.22** Exposed the 4 encoder flags that had no UI: filter type, alpha
  filtering, alpha compression, preprocessing.

### Web — Inspect & Upload
- [x] **5.23** exifr received the whole file instead of the EXIF chunk view, so WebP
  EXIF was parsed from the RIFF header.
- [x] **5.24** `parseWebP` read chunk payloads using the declared size — a truncated
  file threw RangeError and aborted the inspection.
- [x] **5.25** Added the host size pre-check the app already had, plus upload cancel.
- [x] **5.26** EXIF stripping is JPEG-only but the toggle showed for every image.

### Worker
- [x] **5.27** SSRF: `redirect: "follow"` let an allowed URL 302 to a private IP.
  Redirects are now followed manually with every hop revalidated; the blocklist
  covers IPv6, CGNAT, bare-integer/hex IPv4 encodings and URL credentials.
- [x] **5.28** `/api/inspect` buffered the whole upstream body (now 2 MB cap);
  `/api/upload` rejects oversized bodies up front.
- [x] **5.29** Header comment claimed rate limiting that was never implemented.

### Site
- [x] **5.30** Service worker precached extensionless shell paths that GitHub Pages
  301s, breaking offline deep links. Also guards non-GET and cross-origin.
- [x] **5.31** `softwareVersion` was hardcoded; now read from version.properties.

### Tests added
- 63 Android unit tests (was 47) — `FileInfoUtilsTest`, quality-floor regressions
- 32 Worker SSRF tests (`bun test` in worker/)
- 10 site WebP-parser tests (`bun test` in site/)
- Browser E2E: GIF→WebP, GIF→GIF, WebM→WebP, MP4→WebP all produce valid output

### On-device verification (Saga, Android 13 / SDK 33, arm64-v8a)

Each fix was checked against a deliberately reverted build to confirm the bug was
real, then re-checked on the fixed build.

- [x] **5.1 quality floor** — pre-fix: `q=30, min=50`, zero FFmpeg invocations,
  "Conversion failed — no output from FFmpeg". Fixed: `q=30, min=30` → complete.
- [x] **5.2 FFmpeg locale** — verified under a `de-DE` per-app locale
  (`cmd locale set-app-locales`). Pre-fix emitted `-ss 0,469 -to 3,000` and
  FFmpeg returned **"Invalid duration"** (rc=1). Fixed emits `-ss 0.463`, and the
  UI still shows localized text ("0,07 MB"), which is the intended split.
- [x] **5.3 preview cancel** — cancelled a 1280x720 @ 30fps preview mid-render;
  returned cleanly to Ready with the file intact, no stuck spinner.
- [x] **5.12 share routing** — `am start` reported "intent has been delivered to
  currently running top-most instance", exactly one MainActivity instance, and
  the app switched Upload → Convert with the file loaded.
- [x] **5.32** New: MediaStore rows can report `_size=NULL`, which rendered an
  11 MB video as "0.0 MB". Now shows "Unknown size".

Device left with the fixed debug build installed; per-app locale reset, test
media deleted, foreground app restored.

## Compromises / Known Issues

- Site/worker have no CI test step yet — `bun test` must be run manually.
- Web upload has no resume; a cancelled upload restarts from zero.
- `decodeWithCanvas` (WebP fallback for browsers without ImageDecoder) still
  relies on timed capture and can miss frames in background tabs.
- FFmpeg-kit is archived upstream and its FFmpeg 6.0 build cannot decode
  animated WebP, so the Android Convert tab accepts only video and GIF input.
