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
