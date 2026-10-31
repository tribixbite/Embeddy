/**
 * Shared frame utilities: crop-rect clamping, FPS subsampling, and the
 * event-loop yield used by long synchronous loops. Per-frame pixel work
 * (crop/resize/rotate) lives in prepare-frame.ts.
 *
 * WebP encoding is handled exclusively by the streaming Worker encoder
 * (xiaozhuai/webp_encoder WASM) which exposes all libwebp settings.
 */

import type { DecodedFrame, CropRect } from "./types";

/** Yield to the event loop every N frames so progress actually paints. */
export const YIELD_EVERY = 24;

/** Hand control back to the browser so the progress bar can render. */
export function yieldToEventLoop(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/**
 * Convert a normalized CropRect into clamped pixel coordinates.
 * Guarantees a region of at least 1x1 with `px + pw <= srcW` and
 * `py + ph <= srcH`, so callers can size output buffers to exactly what
 * FramePreparer produces (declaring `round(crop.w * srcW)` instead caused
 * WASM "image size mismatch" failures and skewed GIF rows near the edges).
 */
export function cropRectToPixels(
  srcW: number,
  srcH: number,
  crop: CropRect,
): { px: number; py: number; pw: number; ph: number } {
  const px = Math.min(Math.max(0, Math.round(crop.x * srcW)), srcW - 1);
  const py = Math.min(Math.max(0, Math.round(crop.y * srcH)), srcH - 1);
  const pw = Math.min(srcW - px, Math.max(1, Math.round(crop.w * srcW)));
  const ph = Math.min(srcH - py, Math.max(1, Math.round(crop.h * srcH)));
  return { px, py, pw, ph };
}

/**
 * Subsample frames to match a target FPS (for video sources where
 * the decoded FPS may differ from the desired output FPS).
 *
 * The comparison floors the source FPS: the default target is
 * `Math.round(info.fps)`, so a fractional source average (e.g. 10.4 fps
 * rounded to 10) must NOT trigger subsampling — doing so dropped ~4% of
 * frames and flattened every variable per-frame delay for no user benefit.
 *
 * When genuinely subsampling, kept frames retain their real source-timeline
 * spans (delay = time until the next kept frame), preserving both variable
 * timing and total animation duration.
 */
export function subsampleFrames(
  frames: DecodedFrame[],
  sourceFps: number,
  targetFps: number,
): DecodedFrame[] {
  if (targetFps <= 0 || targetFps >= Math.floor(sourceFps)) return frames;

  // Cumulative start time of each source frame, from the real delays
  const startTimes: number[] = new Array(frames.length);
  let totalDuration = 0;
  for (let i = 0; i < frames.length; i++) {
    startTimes[i] = totalDuration;
    totalDuration += frames[i].delay;
  }

  // Pick frame indices at the target rate (deduped against rounding)
  const step = sourceFps / targetFps;
  const picked: number[] = [];
  for (let i = 0; i < frames.length; i += step) {
    const idx = Math.min(Math.round(i), frames.length - 1);
    if (picked.length === 0 || idx !== picked[picked.length - 1]) picked.push(idx);
  }

  return picked.map((idx, k) => {
    const end = k + 1 < picked.length ? startTimes[picked[k + 1]] : totalDuration;
    return {
      rgba: frames[idx].rgba,
      delay: Math.max(1, Math.round(end - startTimes[idx])),
    };
  });
}

