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
  - Two-pass encoding toggle
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

## Compromises / Known Issues

- No unit tests yet (engine logic, metadata parsing)
- No DataStore persistence for user preferences across sessions
- No logging framework (Timber etc)
- Cleanup functions only run on init, not scheduled
- Tab state lost on process death
- Before/after slider uses basic box clipping (works but not pixel-perfect for all aspect ratios)
- Two-pass encoding flag is exposed in UI but not yet wired in ConversionEngine (libwebp_anim doesn't support -pass)
- Size estimation in VideoTrimPlayer still uses 0.3x heuristic
