/**
 * Shared frame processing utilities: cropFrame, resizeFrame, subsampleFrames.
 * Used by ConvertTool (WebP via streaming encoder) and gif-encoder.ts.
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
 * cropFrame produces (declaring `round(crop.w * srcW)` instead caused
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
 * Crop a single RGBA frame using normalized CropRect (0-1 fractions).
 * Uses OffscreenCanvas for sub-rectangle extraction.
 */
export function cropFrame(
  rgba: Uint8Array,
  srcW: number,
  srcH: number,
  crop: CropRect,
): { data: Uint8Array; width: number; height: number } {
  // Shared with callers so declared output dims always match this buffer
  const { px, py, pw, ph } = cropRectToPixels(srcW, srcH, crop);

  // Draw full frame, then extract crop region
  const srcCanvas =
    typeof OffscreenCanvas !== "undefined"
      ? new OffscreenCanvas(srcW, srcH)
      : createCanvas(srcW, srcH);
  const srcCtx = srcCanvas.getContext("2d") as CanvasRenderingContext2D;
  const imageData = srcCtx.createImageData(srcW, srcH);
  imageData.data.set(rgba);
  srcCtx.putImageData(imageData, 0, 0);

  const dstCanvas =
    typeof OffscreenCanvas !== "undefined"
      ? new OffscreenCanvas(pw, ph)
      : createCanvas(pw, ph);
  const dstCtx = dstCanvas.getContext("2d") as CanvasRenderingContext2D;
  dstCtx.drawImage(srcCanvas, px, py, pw, ph, 0, 0, pw, ph);

  const cropped = dstCtx.getImageData(0, 0, pw, ph);
  return { data: new Uint8Array(cropped.data.buffer), width: pw, height: ph };
}

/**
 * Resize a single RGBA frame to fit within maxDimension, preserving aspect ratio.
 * Returns the resized RGBA data and new dimensions.
 */
export function resizeFrame(
  rgba: Uint8Array,
  srcW: number,
  srcH: number,
  maxDim: number,
): { data: Uint8Array; width: number; height: number } {
  if (maxDim <= 0 || (srcW <= maxDim && srcH <= maxDim)) {
    return { data: rgba, width: srcW, height: srcH };
  }

  const scale = Math.min(maxDim / srcW, maxDim / srcH);
  const dstW = Math.round(srcW * scale);
  const dstH = Math.round(srcH * scale);

  // Use OffscreenCanvas for high-quality resize
  const srcCanvas =
    typeof OffscreenCanvas !== "undefined"
      ? new OffscreenCanvas(srcW, srcH)
      : createCanvas(srcW, srcH);
  const srcCtx = srcCanvas.getContext("2d") as CanvasRenderingContext2D;
  const imageData = srcCtx.createImageData(srcW, srcH);
  imageData.data.set(rgba);
  srcCtx.putImageData(imageData, 0, 0);

  const dstCanvas =
    typeof OffscreenCanvas !== "undefined"
      ? new OffscreenCanvas(dstW, dstH)
      : createCanvas(dstW, dstH);
  const dstCtx = dstCanvas.getContext("2d") as CanvasRenderingContext2D;
  dstCtx.drawImage(srcCanvas, 0, 0, dstW, dstH);

  const resized = dstCtx.getImageData(0, 0, dstW, dstH);
  return { data: new Uint8Array(resized.data.buffer), width: dstW, height: dstH };
}

export function createCanvas(w: number, h: number): HTMLCanvasElement {
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  return c;
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

