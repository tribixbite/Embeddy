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
