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
