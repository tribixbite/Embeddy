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

## Phase 6: Closing the Known Gaps (2026-08-11)

- [x] **6.1** CI ran no tests at all. Added a Tests workflow (worker, site,
  Android); `pages.yml` gates the deploy on the site suite and `release.yml`
  runs the unit tests before building APKs.
- [x] **6.2** Accessibility: 28 labels in the Convert panel, one associated.
  Extracted `RangeField`/`NumberField`/`ToggleGroup` (698 → 535 lines) so the
  fix lives in one place. Segmented selectors are now radiogroups with
  `aria-checked` and arrow-key navigation. Verified in-browser: 26 controls all
  resolve an accessible name, 4/4 groups named, 10/10 radios expose state.
- [x] **6.3** Upload retry with linear backoff, 4xx classified as permanent so
  an oversized file fails immediately. Writing the tests caught a bug: cancelling
  before the first XHR registered reported the underlying error and never aborted.
- [x] **6.4** Animated WebP no longer decoded by timed capture. `splitAnimatedWebP`
  re-wraps each ANMF chunk as a still image; verified against the reference
  decoder — 0/2304000 pixels differ on a 30-frame lossy file.
- [x] **6.5** Android accepts animated WebP via the same container-splitting
  technique plus the FFmpeg concat demuxer, working around ffmpeg-kit's inability
  to demux the format. Verified on device: 30 frames in, 30 frames out.

## Compromises / Known Issues

- **Web upload cannot resume.** 0x0.st and catbox.moe expose no resumable
  protocol (no tus, no `Range` PUT), so an interrupted transfer has to restart.
  Mitigated with automatic retry, but a genuine resume needs a different host.
- **Animated-WebP alpha differs by ≤4/255 from the reference decoder** on
  translucent pixels, in the browsers that use the fallback path. This is canvas
  premultiplied-alpha rounding — every opaque pixel is bit-identical and the
  delta persists across all `premultiplyAlpha` modes — not a parsing error.
- **Android WebP frame timing is rounded to milliseconds** by the FFmpeg concat
  script (3 decimal places on seconds), so a 30-frame clip can drift ~30ms overall.
- The Android Convert tab still can't read animated AVIF; no decoder is bundled.

## Phase 7: 2026-09-06 Comprehensive Audit

79 items synthesized from a 7-domain audit (deduped from 87 raw findings). Full detail
(evidence, fixes, test-first notes, workstream assignments WS-A..WS-G) in the audit
MASTER-TODO. Ordered by severity: M1-M10 high, M11-M40 med, M41-M79 low.

- [x] **M1** high/security — SSRF bypass: IPv4-mapped IPv6 hex form (`[::ffff:7f00:1]`) allowed (worker/src/ssrf.ts:45)
- [x] **M2** high/security — SSRF bypass: trailing-dot FQDN (`localhost.`) evades blocklist (worker/src/ssrf.ts:52-56)
- [x] **M3** high — GIF delays multiplied by 10 twice; all GIF conversions 10x slow, NaN on missing GCE (site/src/lib/convert/gif-decoder.ts:99)
- [x] **M4** high — Crop output dims recomputed vs cropFrame's clamped dims; WebP encode fails / GIF corrupts (site/src/lib/convert/gif-encoder.ts:47-57 + ConvertTool.svelte:235-292)
- [x] **M5** high — Video object URL rendered in `<img>`; crop UI broken for all video inputs (site/.../convert/ConvertSettings.svelte:159)
- [x] **M6** high — Stale `options.crop` applied invisibly; checkbox desync on remount, crop leaks across files (ConvertSettings.svelte:50, ConvertTool.svelte:430-448)
- [x] **M7** high — AVIF "Lossless" is a silent no-op; @jsquash/avif has no `lossless` field (site/src/lib/squoosh/compress.ts:92)
- [x] **M8** high — probeAnimatedWebP reads entire file (1-2 GB) into heap before header check; OOM crash on pick (app/.../conversion/ConversionEngine.kt:77)
- [x] **M9** high — Squoosh Share crashes: `squoosh_out/` not in file_paths.xml (SquooshScreen.kt:706, res/xml/file_paths.xml)
- [x] **M10** high — MediaStore.Downloads used unguarded with minSdk 26; crash on Android 8/9 Save (SquooshScreen.kt:715, OutputPreview.kt:177)
- [x] **M11** med/security — SSRF guard purely lexical; no DNS resolution (rebinding vector) (worker/src/ssrf.ts:52,104-124)
- [x] **M12** med/privacy — Strip-metadata silently fails for HEIF/HEIC; GPS EXIF uploaded publicly (app/.../upload/UploadEngine.kt:221)
- [x] **M13** med — Upload relay double-buffers file; 95 MB cap exceeds 128 MB isolate memory (worker/src/index.ts:51,259,292-298)
- [x] **M14** med — GIF blob wraps whole gifenc capacity buffer; up to ~2x trailing junk bytes (site/src/lib/convert/gif-encoder.ts:120-121)
- [x] **M15** med — No Worker "error" listener; failed worker load hangs conversion forever (site/src/lib/convert/streaming-encoder.ts:39-56)
- [x] **M16** med — targetFps rounding drops frames and flattens variable GIF/WebP timing (site/src/lib/convert/encoder.ts:102-118)
- [x] **M17** med — "Change file" during conversion lands on error screen ("Encoder aborted") (ConvertTool.svelte:424-448)
- [x] **M18** med — Streaming-video-to-GIF is uncancellable; can dead-end on blank screen (ConvertTool.svelte:380-391,504)
- [x] **M19** med — NumberField rewrites input mid-typing; "0.5" becomes 5 (site/.../shared/NumberField.svelte:38-44)
- [x] **M20** med — CropOverlay aspect lock violated at bounds (h clamps, w doesn't follow) (CropOverlay.svelte:137-149)
- [x] **M21** med — Animated lossy WebP with alpha reports compression "unknown" (ALPH not skipped) (site/src/lib/inspect/webp-parser.ts:209)
- [x] **M22** med — "Exif\0\0"-prefixed WebP EXIF chunks silently dropped (exifr rejects prefix) (webp-parser.ts:220)
- [x] **M23** med — Cancelling an upload lands on error screen, not ready screen (site/.../upload/UploadTool.svelte:88-107)
- [x] **M24** med — generatePreview skips animated-WebP pre-decode; preview is a single still (ConversionEngine.kt:587)
- [x] **M25** med — Multi-segment stitching silently dropped for animated WebP (only segment 1 kept) (ConversionEngine.kt:408)
- [x] **M26** med — SizeEstimation disagrees with FFmpeg scale filter (either-axis maxDimension + single-exact-dim) (app/.../util/SizeEstimation.kt:16-41)
- [x] **M27** med — CancellationException caught as failure; Cancel shows bogus Error card in 3 ViewModels (SquooshViewModel.kt:109, UploadViewModel.kt:70, InspectViewModel.kt:52)
- [x] **M28** med — New input doesn't cancel in-flight conversion/preview; wrong file's result shown (MainViewModel.kt:118-169)
- [x] **M29** med — VideoTrimPlayer playback loop keeps a released ExoPlayer; state leaks across uri change (VideoTrimPlayer.kt:99-147)
- [x] **M30** med — Trim player composes ExoPlayer for GIF/animated-WebP it can't decode; dead black preview (ConvertScreen.kt:120-140)
- [x] **M31** med — Raising FPS slider after load silently ignored for batch-decoded video (ConvertTool.svelte:196,229)
- [x] **M32** med — GIF output discards transparency; transparent pixels become black (site/src/lib/convert/gif-encoder.ts:100-108)
- [x] **M33** med — Default `mixed:true` makes Lossless toggle a no-op and hides a still-applied quality slider (ConvertSettings.svelte:35,243-277)
- [x] **M34** med — Inspect accepts/advertises video but exifr supports no video format (InspectTool.svelte:48,140-142)
- [x] **M35** med — Copy promises Discord/Slack/Twitter previews; only a Discord card exists, 4 fetched fields unused (inspect.astro:9, EmbedPreview.svelte)
- [x] **M36** med — ABI selection cosmetic; all choices download the generic releases link (site/src/components/AbiPicker.svelte:70-91)
- [x] **M37** med — Homepage "files never leave your device" claim false for Upload and Inspect (site/src/pages/index.astro:108-112)
- [x] **M38** med — Host chips advertise 512/200 MB but relay caps at 95 MB (site/.../upload/HostSelector.svelte:16-17)
- [x] **M39** med — ColorSpace YUV444/RGB silently auto-substituted to bgra by libwebp_anim; dead options (ConversionConfig.kt:74, ConversionEngine.kt:381-383)
- [x] **M40** med — TASKS 3.1 doesn't hold: 25+ user-visible hardcoded literals remain (multiple app UI files)
- [x] **M41** low/security — isPrivateIpv6 misses fec0::/10 and NAT64 64:ff9b::/96 embeddings (worker/src/ssrf.ts:41-49)
- [x] **M42** low — Missing Content-Length skips pre-buffer 413 check (worker/src/index.ts:249-257)
- [x] **M43** low — safeFetch never drains/cancels redirect-hop bodies (worker/src/ssrf.ts:110-123)
- [x] **M44** low — Redirect-blocked policy violations return 502 instead of 403 (worker/src/index.ts:175-178)
- [x] **M45** low — Inspect client surfaces raw JSON `{"error":...}` as the user-facing message (site/src/lib/inspect/proxy.ts:20-23)
- [x] **M46** low — Site: source loop count parsed but never propagated; play-once inputs loop forever (site/src/lib/convert/webp-frames.ts:44, types.ts)
- [x] **M47** low — Sub-100ms video decodes zero frames; dead Convert button (site/src/lib/convert/video-decoder.ts:49-50)
- [x] **M48** low — pushFrame doc claims buffer is neutered; a copy is transferred (streaming-encoder.ts:64-68)
- [x] **M49** low — decodeWebP JSDoc documents nonexistent targetFps param (webp-decoder.ts:14-21)
- [x] **M50** low — NumberField ignores min/max; negative values reach the encoders (NumberField.svelte:38-44)
- [x] **M51** low — CropOverlay top/bottom handles inert under aspect lock (CropOverlay.svelte:127-149)
- [x] **M52** low — CropOverlay move-drag recenters crop on pointer (no grab offset) (CropOverlay.svelte:105-110)
- [x] **M53** low — Custom aspect width 0/empty degenerates crop (division by zero) (CropOverlay.svelte:43,176)
- [x] **M54** low — Aspect chips radiogroup unreachable by keyboard (no arrow-key handler) (CropOverlay.svelte:213-230)
- [x] **M55** low — Crop/resize/GIF-encode loops never yield; progress never paints, UI freezes (ConvertTool.svelte:237-263, gif-encoder.ts:89-109)
- [x] **M56** low — ToggleGroup arrow keys change selection without moving focus (ToggleGroup.svelte:25-35)
- [x] **M57** low — detectMimeType calls any "ftyp" file AVIF; HEIC hits the wrong decoder (site/src/lib/squoosh/compress.ts:57-58)
- [x] **M58** low — PNG shows a do-nothing Lossless checkbox that leaks state into WebP (SquooshSettings.svelte:25,96-106)
- [x] **M59** low — Resize claims high-quality downscaling but never sets imageSmoothingQuality (compress.ts:19-40)
- [x] **M60** low — Squoosh accepts image/* but only 4 formats decode; GIF users never pointed at /tools/convert (SquooshTool.svelte:95)
- [x] **M61** low — AbiPicker copy stale and wrong: v0.1.12 (actual 0.1.51), "Android 7.0+" (minSdk 26 = 8.0) (AbiPicker.svelte:94)
- [x] **M62** low — FeatureCards.svelte dead code describing nonexistent features (site/src/components/FeatureCards.svelte)
- [x] **M63** low — ToolNav comment says "3 browser tools", lists 4 (ToolNav.svelte:3-4)
- [x] **M64** low — keyframeInterval maps to `-g` which libwebp_anim never reads; dead setting (ConversionEngine.kt:384)
- [x] **M65** low — Complete emitted without verifying output exists; Done points at a nonexistent file (ConversionEngine.kt:283)
- [x] **M66** low — FFprobe fallback stubs rotation/frameCount to 0 (ConversionEngine.kt:158)
- [x] **M67** low — Probed rotation never applied; portrait videos report swapped dimensions (ConversionEngine.kt:119)
- [x] **M68** low — CleanupWorker uses `delete()` on non-empty frames_* dirs; orphaned PNGs never reclaimed (app/.../util/CleanupWorker.kt:26)
- [x] **M69** low — SquooshResult reports subsampled decode dims as "original" (SquooshEngine.kt:66)
- [x] **M70** low — App: WebP loop count parsed but never propagated; output always loops forever (WebPFrameSplitter.kt:115)
- [x] **M71** low — SegmentMergeTest tests a private copy, not MainViewModel's production function (SegmentMergeTest.kt:14)
- [x] **M72** low — navigateToConvert SharedFlow (replay=0) emission dropped before collector exists (MainViewModel.kt:48)
- [x] **M73** low — Indeterminate-progress fallback can never trigger; previews always show "0s" (ConversionProgress.kt:45)
- [x] **M74** low — Disk/ContentResolver I/O on the main thread in saves, picker queries, preview delete (SquooshScreen.kt:715, OutputPreview.kt:177, viewmodels)
- [x] **M75** low — Coil ImageDecoderDecoder without API-28 guard; animated previews blank on API 26-27; ImageLoader leak (OutputPreview.kt:60, PreviewCard.kt:52)
- [x] **M76** low — ConversionConfig.loop consumed by engine but has no UI and isn't persisted (ConversionConfig.kt:25)
- [x] **M77** low — Dead plumbing: InspectScreen.initialFileUri and ConversionState.Picking have zero producers (InspectScreen.kt:74, ConversionConfig.kt:93)
- [x] **M78** low — Six unused strings.xml entries (res/values/strings.xml:12-13,19,62,65,69)
- [x] **M79** low — UploadViewModel lacks SavedStateHandle/persistence its sibling tabs have (UploadViewModel.kt:18-51)

## Phase 8: 2026-09-08 Feature Round

- [x] **8.1** site: AbortSignal through decodeVideo/probeVideo/streamDecodeVideo; ConvertTool aborts orphaned decodes on reset (was TODO from phase 7)
- [x] **8.2** app: stitching reliability — zero-length segments dropped, segments sorted; edge cases pinned by tests
- [x] **8.3** app: video output formats MP4 + Original (openh264/aac, libvpx/opus; quality→bitrate mapping; WebP command pinned unchanged)
- [x] **8.4** app: per-segment volume control (TrimSegment.volume, atrim+volume+concat graph, audio-less handling, 0-200% slider)
- [ ] **8.5** on-device: verify MP4/Original encodes (A/V sync, volume steps), no-audio inputs, container mapping, share/save mime — see feat-app-stitch report checklist
- [x] **8.6** site: percent + ETA on convert progress (per-phase clock, per-attempt reset); dead wasm-webp dep removed
- [x] **8.7** app: ETA on conversion card; per-attempt elapsed baseline fix

## Phase 9: 2026-10-31 Audit, Video-Editor Scope, Play Store Readiness

Scope widened: Embeddy is both an embed-media optimizer and a quick personal
video editor (trim, splice/join, convert). Two fresh audits (site/worker: 3 high,
16 med, 6 low; app: 4 high, 10 med, 6 low), all implemented with tests.
Suites: site 143 → 249, worker 53 → 67, app 194 → 301. Lint 0 errors.

### Play Store readiness
- [x] **9.1** targetSdk/compileSdk 36 (required since 2026-08-31), AGP 8.9.3 → 8.13.1
- [x] **9.2** Removed READ_MEDIA_IMAGES/VIDEO + READ_EXTERNAL_STORAGE (unused; Play photo/video policy); WRITE_EXTERNAL_STORAGE maxSdk 28
- [x] **9.3** Backup rules (prefs only), predictive back, themed-icon monochrome layer
- [x] **9.4** versionCode scheme widened (old one failed every release from CI run 100)
- [x] **9.5** AAB built in CI as a separate artifact; ABI splits auto-disabled for bundle tasks (AGP 8.13 bug 402800800)
- [x] **9.6** Privacy policy at /privacy/, linked from site footer and in-app About sheet
- [x] **9.7** Store graphics (512 icon, 1024x500 feature graphic) + generator script; short description no longer claims "fully offline"
- [x] **9.8** docs/play-store.md: Data safety answers, content rating, signing options, closed-test rule
- [x] **9.9** All 18 native libs verified 16 KB-aligned
- [x] **9.10** Phone screenshots retaken at 900x1800 (2:1); old set was 540x1200 (2.22:1, over Play's 2:1 max)
- [ ] **9.11** Play Console steps only the account owner can do (see docs/play-store.md)

### Web (site + worker)
- [x] **9.20** Upload metadata strip rewritten (JPEG/PNG/WebP walker), fails closed — previously uploaded GPS on any error
- [x] **9.21** Convert: cancellable decode/encode, single-pass frame transform (no triple frame copies), routed pipeline in lib with tests
- [x] **9.22** Squoosh "Change file" mid-compress blank screen; native image decode
- [x] **9.23** SW update prompt instead of mid-session takeover; workers/WASM cached offline; chunk-load reload
- [x] **9.24** Worker upstream timeouts + response caps; /api/inspect tests
- [x] **9.25** A11y: named nav links, progressbar roles, 44px targets, drop-zone keyboard
- [x] **9.26** Self-hosted fonts; shared Layout (no duplicated head)
- [x] **9.27** Features: paste, page-wide drop, PWA share target/file handlers, share sheet, send-to-Upload, trim, speed, rotate/flip, frame grab, MP4/WebM export (Mediabunny, MPL-2.0), multi-clip join

### Android
- [x] **9.40** Copy/probe failure no longer crashes (H1); cancelled upload actually stops (H2)
- [x] **9.41** Per-file EditSpec: trim/segments reset per input, survive preset change and process death
- [x] **9.42** Squoosh decode memory budget; OOM → error state
- [x] **9.43** Even video dimensions, per-frame palettes, SAF input for FFmpeg, debounced settings, "keep original" res/fps, direct MP4 bitrate for target size, -map_metadata -1
- [x] **9.44** Features: GIF output, mute, extract audio (m4a), speed 0.25-4x, rotate/flip, free crop, frame grab → Squoosh, join videos, reverse (≤10 s), image sequence → WebP/GIF/MP4, SEND_MULTIPLE
- [x] **9.45** About sheet with licences (FFmpeg GPL-3.0 build) + privacy/source links
- [x] **9.46** Found on device: "Add clips" from a loaded video (join was only reachable from the empty screen) — single → join promotion keeps trim/segments
- [x] **9.47** Found on device: .m4a saved/shared as application/octet-stream; portrait output preview cropped; blank box for audio; meaningless "quality" on video/audio
- [x] **9.49** Found on device: Inspect embed card left an empty block when the og:image failed (429)
- [x] **9.48** Found on device: sheets under status bar; Inspect keyboard covering results; rotate gave no visible/announced state; duplicate (i) icon; 36dp inspect button

### On-device verification (Saga, Android 13, arm64) — 2026-10-31
Verified: SDK-36 install requests no storage/media permissions; edge-to-edge
insets; SAF pick → probe; MP4 2x speed (720x406 even, 3.02 s, AAC kept, source
GPS tag stripped); GIF rotate 90 (405x720, 36 frames); M4A extraction (AAC
3.0 s); Save → MediaStore Downloads; Edit again keeps file + edit; unreadable
input → error card, no crash; About sheet; Inspect http:// → https; frame grab →
Squoosh hand-off → 80% smaller WebP.

Also verified: Add clips on a trimmed video → join (trim carried as clip range),
landscape+audio joined with silent rotated portrait → 7.37 s MP4 (portrait
upright, letterboxed) and 7.37 s M4A (silence padded); process-death restore of
file + edit after reinstall; About sheet; edge-to-edge sheets.

Not yet verified on device: image sequence, reverse, crop overlay, Upload cancel (would publish to a
public host if cancel failed), API 26-28 save path, real share-sheet intents
from Photos/Files (adb-issued grants do not work without READ_MEDIA_*).

## Phase 10: 2026-11-01 Follow-ups after first push

Pushed phase 9 (merged the CI's v0.1.53 pin commit, no rebase). Worker deployed
(version be93b934); SSRF probes to 169.254.169.254 and [::ffff:127.0.0.1] → 403.

### Web
- [x] **10.1** Speed changes keep audio in MP4/WebM export: streaming WSOLA time-stretch
  (`lib/convert/time-stretch.ts`, pitch preserved like FFmpeg atempo) via Mediabunny's
  `audio.process`. Verified in headless Chromium: 3 s clip → 1.51 s @2x / 6.01 s @0.5x,
  440 Hz tone stays 440 Hz, loudness unchanged; trimmed 2 s window → 1.02 s / 4.01 s.
- [x] **10.2** Squoosh compression runs in a Web Worker (`squoosh/compress-client.ts`), kept warm
  between runs; main-thread fallback when module Workers are unavailable. New Cancel button
  terminates the encode. Built-site check in Chromium: 2400x1800 PNG → WebP 445→97 KB in 1.9 s;
  worst main-thread stall during an AVIF encode 4 ms; Cancel → terminate() → next run uses a new Worker.
- [x] **10.3** Web join: each added clip has its own Trim (preview player + range, `lib/convert/join-clips.ts`),
  clips are validated on add (no-video files rejected with a message), and speed now applies to the
  whole join with pitch kept. Crop/rotate stay source-only (defined against one frame). Chromium e2e:
  3 s source + 4 s clip trimmed to 1–3 s at 2× → 2.52 s WebM, audio 440 Hz then 660 Hz; TrimControls
  ids unique per instance.

### Android
- [x] **10.4** Upload: Start over no longer sweeps `upload_temp/` in the background (it could delete
  the next upload's staged copy mid-transfer). Leftovers from a killed process are swept once per
  ViewModel, and uploads wait for that sweep.
- [x] **10.5** Lint follow-ups: plurals for "Joining N clips" / "N images" (read "1 clips");
  API-28/31 MediaMetadataRetriever keys guarded at their real API levels (sample rate / bit depth
  were queried on 28-30 where the keys don't exist); display-only String.format calls take the
  user locale explicitly.

- [x] **10.7** Found on device (S938U1, Android 17): Images → animation failed for EVERY image with
  "Cannot open <name>" — a bounds-only BitmapFactory decode returns null by design, and the code
  chained `?: throw` after it. Verified after fix: 3 JPEGs, reordered → GIF frames in the new order, 1.56 s.
- [x] **10.8** Found on device: crop corner handles were not grabbable at the corners — the preview
  box's rounded clip also clips hit-testing, so a touch on a handle scrolled the page. Only the media
  layer is clipped now. Verified: drags starting exactly on the TL/BR corners resize the crop; reverse +
  crop MP4 → 778x418 (even), 6.0 s, AAC kept, frames in reverse order.

### Store
- [x] **10.6** Feature graphic set in Inter (site typeface) via a private fontconfig; was DejaVu.

## Phase 11: 2026-11-01 Play submission prep, lint, docs

Pushed phase 10 as v0.1.55. Signing key confirmed: `~/git/embeddy-release.jks` (alias `embeddy`,
RSA 4096, valid to 2053); all 4 CI secrets set; v0.1.55 APKs and `.aab` both signed with SHA-256
`612c62…94fe` (= F-Droid `AllowedAPKSigningKeys`).

- [x] **11.1** One versionCode ordering across channels: every artifact is `base*10 + N` — universal 0,
  armeabi-v7a 1, arm64-v8a 2, x86_64 3, Play bundle 9. Before, the universal APK and the Play bundle
  carried the bare base (10055) below the split APKs (100551–3), so a GitHub/F-Droid split install
  could never take a Play update.
- [x] **11.2** CI writes the F-Droid changelog for each split versionCode (fdroidserver matches the exact
  code; only a base-code file existed, which no F-Droid build uses), capped at F-Droid's 500 chars.
- [x] **11.3** WorkManager's unused `SystemForegroundService` + `FOREGROUND_SERVICE` stripped from the
  merged manifest (one fewer Play policy declaration).
- [x] **11.4** Lint: KTX `toUri`/`createBitmap`/`scale` (21), Compose modifier parameter order (2),
  `tools:targetApi` for `enableOnBackInvokedCallback`, configure-flag dashes, ChromeOS ABI false
  positive (lint can't evaluate the spread). `mipmap-anydpi-v26` kept — merging it breaks the native
  aapt2's resource linking; ignored in `app/lint.xml`.
- [x] **11.5** `docs/play-store.md` rewritten as the single owner checklist (key backup, account,
  PEPK key upload, bundle download, listing, app content, Data safety, closed test, production) with
  commands. `scripts/play-listing-text.py` prints paste-ready listing text (unwraps the hard-wrapped
  fastlane file, checks Play limits).
- [x] **11.6** README rewritten for the editor scope; GitHub repo description, https homepage and
  topics updated; F-Droid README signing section marked done.
- [ ] **11.7** Dependency upgrades lint reports (Compose BOM 2024.12→2026.09, Kotlin 2.0→2.4, AGP 8.13→9.5,
  Media3 1.5→1.11, lifecycle, navigation, work…). Deliberately deferred past the first Play
  submission: large surface, gradle.lockfile + F-Droid reproducibility churn.
- [ ] **11.8** Separate Play upload key (TODO in `release.yml`) — optional, owner decision.
- [x] **11.9** F-Droid recipe points at v0.1.56 (100561–3), the first release with per-split changelogs; verified its APKs carry those codes and the pinned signer.
- [x] **11.11** Crop editor closes on every new input (share intents and Start over too), keyed on the
  Loading/Idle states every input path passes through; was reset only by this screen's pickers.
- [x] **11.10** Device re-verification on the second Saga (192.168.0.144, Android 13), 2026-11-01 evening;
  checked out/in via `/sdcard/agent-sharing.md` (Pixel was checked out by another agent).
  Passed: animated WebP opens with preview; crop editor open → new VIEW input closes it; Squoosh exact
  200x100 from 320x240 (scale + center crop); MP4 → MP4 convert completes; About → Privacy policy opens
  embeddy.link/privacy/ in Chrome; CleanupWorker dispatched by SystemJobService (no foreground service)
  and correctly deferred as not yet due. 2026-11-02 on the same Saga: five PNGs pushed by adb, picked in
  order through Images → animation (documentsui multi-select) → 5 images, 0.5 s each → animated WebP
  15.5 KB. Start over returns to the empty picker. (`am start` SEND can't grant URIs in extras; use the
  picker or VIEW.)
- [x] **11.12** Package ID → `tribixbite.embeddy` (v0.1.58; Kotlin namespace stays `app.embeddy`).
  Debug build installs as `tribixbite.embeddy.debug`; AAB carries the new ID and the release-key signature.
  F-Droid recipe renamed to `fdroid/tribixbite.embeddy.yml`.
- [x] **11.13** Play Console (owner). Found: app created as tribixbite.embeddy with a Google-generated
  app signing key (not our key), closed-test release started but unconfirmed, no bundle uploaded, and an
  account-inactivity warning. The agent's browser bridge lost its extension connection, so no console
  changes were made. Next steps are in docs/play-store.md §2–4 and §5–7.
  Play must sign with OUR release key (owner, 2026-11-02): switch via App signing → Change key before
  the first upload. Added `play-key-export.yml` (manual CI job: pepk with the secret keystore password,
  cert and pepk.jar hash pinned; dry-run with a throwaway key passed). Needs the console's
  encryption_public_key.pem — steps in docs/play-store.md "Switch Play to our release key".
  DONE 2026-11-02: export run 41492087463 (zip cert verified 61:2C…94:FE), owner saved Change key; the
  console now shows 61:2C…94:FE as the app signing key. Remaining: bundle upload + listing (§5–7).
  DONE 2026-11-03: content rating (ESRB Everyone, PEGI 3, IARC 3+); Data safety "no data collected or
  shared" (owner's call: Upload/Inspect are explicit user actions, not collection for our purposes);
  category Video Players & Editors, contact admin@techneesh.com + https://embeddy.link/; default store
  listing (text, icon, feature graphic, 6 screenshots, AI-asset label "don't label": SVG art drawn by a
  script, real screenshots); Alpha closed track: 178 countries, testers = email list "Internal"
  (bossamberstone, willstone, mknarus), feedback admin@techneesh.com, release 100589 (0.1.58).
  15 changes sent for review with Managed publishing off, to clear the overdue dormant-account notice
  (fix = publish an app; email + phone were already verified).
- [ ] **11.14** After Play approves the Alpha release: confirm the dormant-account banner clears, send
  testers the opt-in link (Testers tab), and grow the list to ≥ 12 opted-in testers for 14 days
  before applying for production. Optional: upload native debug symbols (the only release warning).
  2026-11-06: owner switched the Alpha testers from the "Internal" email list to a Google Group.
- [x] **11.15** F-Droid recheck (2026-11-06): recipe passes `fdroid lint` against fdroiddata's real
  category list and is now in `fdroid rewritemeta` canonical form (fdroiddata CI rejects anything else;
  the stripped comments moved to fdroid/README.md); `fdroid scanner` on v0.1.58: 0 errors, 0 warnings.
- [ ] **11.16** F-Droid (owner): push the recipe to a branch of gitlab.com/tribixbite/fdroiddata so its CI
  runs the real buildserver build + reproducibility check (not yet verified anywhere outside our CI),
  then open the fdroiddata MR. Steps: fdroid/README.md "Submitting".
