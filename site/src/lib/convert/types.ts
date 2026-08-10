/** Shared types for the Convert tool pipeline. */

/** A single decoded frame ready for encoding */
export interface DecodedFrame {
  /** Raw RGBA pixel data (width * height * 4 bytes) */
  rgba: Uint8Array;
  /** Frame display duration in milliseconds */
  delay: number;
}

/** Normalized crop rectangle (all values 0-1 fractions of source dimensions) */
export interface CropRect {
  /** Left edge as fraction of source width */
  x: number;
  /** Top edge as fraction of source height */
  y: number;
  /** Crop width as fraction of source width */
  w: number;
  /** Crop height as fraction of source height */
  h: number;
}

/** Source file metadata extracted during decoding */
export interface SourceInfo {
  /** Canvas width in pixels */
  width: number;
  /** Canvas height in pixels */
  height: number;
  /** Number of frames */
  frameCount: number;
  /** Total animation duration in ms */
  totalDuration: number;
  /** Average frame rate */
  fps: number;
  /** Source format */
  format: "gif" | "video" | "webp";
  /**
   * True when the decoder stopped early because of the frame/memory budget,
   * so `frameCount` is less than the source actually contains.
   */
  frameCapped: boolean;
}

/** Encoding options for the output */
export interface ConvertOptions {
  /** Quality 1-100 (lossy mode for WebP; palette quality for GIF) */
  quality: number;
  /** Lossless encoding — WebP only */
  lossless: boolean;
  /** Max dimension (0 = no resize) */
  maxDimension: number;
  /** Loop count (0 = infinite) */
  loops: number;
  /** Target FPS for video/WebP sources (0 = preserve original) */
  targetFps: number;
  /** Output format */
  outputFormat: "webp" | "gif";
  /** Crop region or null for no crop */
  crop: CropRect | null;
  /** Target file size in bytes (0 = disabled, encode at fixed quality) */
  targetSizeBytes: number;
  /** Compression effort 0-6. Higher = slower but smaller output.
   *  0 = fastest (minimal compression), 6 = slowest (best compression).
   *  Default: 4 (good balance). */
  method: number;
  /** Minimize output size — reorders chunks for smallest file.
   *  Slower (O(n²) for many frames) but produces significantly smaller output. */
  minimizeSize: boolean;
  /** Preserve exact RGB values under transparent areas.
   *  Prevents ghosting on dark/OLED content but increases file size. */
  exact: boolean;
  /** Allow mixed lossy/lossless frames — libwebp picks per-frame whichever is smaller.
   *  When true, per-frame `lossless` flag is ignored (libwebp overrides it). */
  mixed: boolean;
  /** Min keyframe distance (0 = auto). Keyframes reset error accumulation
   *  and enable seeking. Lower = more keyframes = larger but more scrub-friendly. */
  kmin: number;
  /** Max keyframe distance (0 = auto). */
  kmax: number;
  /** Spatial Noise Shaping strength 0-100. Higher = more aggressive noise reduction.
   *  Default: 50 */
  snsStrength: number;
  /** Deblocking filter strength 0-100.
   *  Default: 60 */
  filterStrength: number;
  /** Filter sharpness 0-7. Higher values = sharper but potentially more artifacts.
   *  Default: 0 */
  filterSharpness: number;
  /** Filter type: 0=simple (fast), 1=strong (default, better quality). */
  filterType: number;
  /** Auto-adjust deblocking filter strength (0 or 1).
   *  When enabled, overrides filterStrength with auto-computed value. Default: 0 */
  autofilter: number;
  /** Alpha channel compression quality 0-100.
   *  Lower = smaller file, worse alpha quality. Default: 100 */
  alphaQuality: number;
  /** Alpha compression algorithm: 0=none, 1=lossless.
   *  Default: 1 */
  alphaCompression: number;
  /** Alpha filtering: 0=none, 1=fast, 2=best.
   *  Default: 1 */
  alphaFiltering: number;
  /** Number of entropy-analysis passes 1-10. More passes = smaller file, slower encode.
   *  Default: 1 */
  passes: number;
  /** Preprocessing filter: 0=none, 1=segment-smooth, 2=pseudo-random dithering.
   *  Default: 0 */
  preprocessing: number;
  /** Near-lossless quality 0-100 (100=off). With lossless mode, trades minimal
   *  visual loss for significantly smaller files. Default: 100 */
  nearLossless: number;
  /** Use sharp YUV conversion for better chroma quality on sharp edges (0 or 1).
   *  Default: 0 */
  sharpYuv: number;
  /** Target output size in bytes (0=off). When set, encoder adjusts quality internally
   *  to hit the target. Overrides quality setting. Default: 0 */
  targetSize: number;
  /** Max number of segments 1-4. Fewer segments = faster but lower quality.
   *  Default: 4 */
  segments: number;
  /** Partition limit 0-100 (0=off). Quality degradation allowed to fit partitions.
   *  Default: 0 */
  partitionLimit: number;
  /** Reduce memory usage at cost of CPU (0 or 1). Useful for very large images.
   *  Default: 0 */
  lowMemory: number;
  /** Map quality to JPEG-equivalent scale (0 or 1).
   *  Default: 0 */
  emulateJpegSize: number;
}

/** Progress callback for long-running operations */
export interface ConvertProgress {
  /** Current phase description */
  phase: "decoding" | "cropping" | "resizing" | "encoding";
  /** 0-100 percentage within current phase */
  percent: number;
  /** Current frame being processed */
  frame: number;
  /** Total frames */
  total: number;
}
