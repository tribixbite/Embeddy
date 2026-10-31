/**
 * Pure routing and retry policy for the Convert pipeline.
 *
 * Lives outside ConvertTool.svelte so the decisions that decide whether a
 * file is buffered or streamed, and how the adaptive target-size loop walks
 * quality down, are unit-testable (M2, L3).
 */

import type { SourceInfo } from "./types";

/** RGBA budget for buffering every decoded frame (batch path). */
export const BATCH_MEMORY_LIMIT = 512 * 1024 * 1024;
/** Frame cap of the batch decoders — beyond this a batch decode truncates. */
export const MAX_BATCH_FRAMES = 1500;
/** Quality decrement per adaptive attempt. */
export const ADAPTIVE_QUALITY_STEP = 10;
/** Lowest quality the adaptive loop tries before giving up. */
export const ADAPTIVE_MIN_QUALITY = 5;

/** Limits for {@link chooseVideoRoute}; defaults match the decoders. */
export interface RouteLimits {
  memoryLimit: number;
  maxBatchFrames: number;
}

/**
 * Decide whether a video is decoded into memory ("batch") or decoded lazily
 * frame-by-frame into the streaming encoder ("streaming").
 *
 * Streams when the buffered RGBA would exceed the memory budget OR when the
 * frame count exceeds the batch cap — a small-but-long video used to fit the
 * budget, take the batch path and be silently cut at 1500 frames (M2).
 */
export function chooseVideoRoute(
  info: Pick<SourceInfo, "width" | "height" | "totalDuration">,
  fps: number,
  limits: RouteLimits = { memoryLimit: BATCH_MEMORY_LIMIT, maxBatchFrames: MAX_BATCH_FRAMES },
): "batch" | "streaming" {
  const frameCount = Math.floor((info.totalDuration / 1000) * fps);
  const bytes = frameCount * info.width * info.height * 4;
  if (bytes > limits.memoryLimit) return "streaming";
  if (frameCount > limits.maxBatchFrames) return "streaming";
  return "batch";
}

/**
 * Batch-decoded video frames exist only at the load-time rate; raising the
 * FPS slider above it needs a re-decode (M31). GIF/WebP sources carry their
 * own frames and streaming decodes on demand, so neither re-decodes.
 */
export function needsFpsRedecode(
  info: Pick<SourceInfo, "format" | "fps">,
  targetFps: number,
  streaming: boolean,
): boolean {
  return info.format === "video" && !streaming && targetFps > info.fps;
}

/** Options for {@link encodeToTarget}. */
export interface TargetSizeOptions {
  /** Quality of the first attempt */
  startQuality: number;
  /** Size goal in bytes; 0 or less = single encode, no retries */
  targetBytes: number;
  /** Decrement per retry (default {@link ADAPTIVE_QUALITY_STEP}) */
  step?: number;
  /** Lowest quality to try (default {@link ADAPTIVE_MIN_QUALITY}) */
  minQuality?: number;
  /** Called before every encode with the 1-based attempt number */
  onAttempt?: (attempt: number, quality: number) => void;
}

/** Outcome of {@link encodeToTarget}. */
export interface TargetSizeResult {
  blob: Blob;
  /** Quality of the returned blob */
  quality: number;
  /** False when even the lowest quality tried is over the target (L3) */
  fits: boolean;
  attempts: number;
}

/**
 * Encode, then re-encode at decreasing quality until the output fits.
 * Always encodes at least once — the quality slider reaches 1, below the
 * floor, and a `while (q >= min)` guard would have skipped encoding entirely.
 */
export async function encodeToTarget(
  encode: (quality: number) => Promise<Blob>,
  opts: TargetSizeOptions,
): Promise<TargetSizeResult> {
  const step = opts.step ?? ADAPTIVE_QUALITY_STEP;
  const minQuality = opts.minQuality ?? ADAPTIVE_MIN_QUALITY;
  let quality = opts.startQuality;
  let attempts = 0;

  for (;;) {
    attempts++;
    opts.onAttempt?.(attempts, quality);
    const blob = await encode(quality);
    const fits = opts.targetBytes <= 0 || blob.size <= opts.targetBytes;
    const next = quality - step;
    if (fits || next < minQuality) return { blob, quality, fits, attempts };
    quality = next;
  }
}
