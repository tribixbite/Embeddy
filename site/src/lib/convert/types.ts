/** Shared types for the Convert tool pipeline. */

/** A single decoded frame ready for WebP encoding */
export interface DecodedFrame {
  /** Raw RGBA pixel data (width * height * 4 bytes) */
  rgba: Uint8Array;
  /** Frame display duration in milliseconds */
  delay: number;
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
  /** Source format ("gif" | "video") */
  format: "gif" | "video";
}

/** Encoding options for the animated WebP output */
export interface ConvertOptions {
  /** WebP quality 1-100 (lossy mode) */
  quality: number;
  /** Lossless encoding (0 = lossy, 1 = lossless) */
  lossless: boolean;
  /** Max dimension (0 = no resize) */
  maxDimension: number;
  /** Loop count (0 = infinite) */
  loops: number;
  /** Target FPS for video sources (0 = preserve original) */
  targetFps: number;
}

/** Progress callback for long-running operations */
export interface ConvertProgress {
  /** Current phase description */
  phase: "decoding" | "resizing" | "encoding";
  /** 0-100 percentage within current phase */
  percent: number;
  /** Current frame being processed */
  frame: number;
  /** Total frames */
  total: number;
}
