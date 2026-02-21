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

## Compromises / Known Issues

- No unit tests yet (engine logic, metadata parsing)
- No logging framework (Timber etc)
- Cleanup functions only run on init, not scheduled
- Tab state lost on process death
