/**
 * Shared frame processing utilities: cropFrame, resizeFrame, subsampleFrames.
 * Used by ConvertTool (WebP via streaming encoder) and gif-encoder.ts.
 *
 * WebP encoding is handled exclusively by the streaming Worker encoder
 * (xiaozhuai/webp_encoder WASM) which exposes all libwebp settings.
 */

import type { DecodedFrame, CropRect } from "./types";

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
  // Convert fractional crop to pixel coordinates, clamped to source bounds
  const px = Math.max(0, Math.round(crop.x * srcW));
  const py = Math.max(0, Math.round(crop.y * srcH));
  const pw = Math.min(srcW - px, Math.max(1, Math.round(crop.w * srcW)));
  const ph = Math.min(srcH - py, Math.max(1, Math.round(crop.h * srcH)));

  if (pw <= 0 || ph <= 0) {
    return { data: rgba, width: srcW, height: srcH };
  }

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
 */
export function subsampleFrames(
  frames: DecodedFrame[],
  sourceFps: number,
  targetFps: number,
): DecodedFrame[] {
  if (targetFps <= 0 || targetFps >= sourceFps) return frames;

  const step = sourceFps / targetFps;
  const result: DecodedFrame[] = [];
  const newDelay = Math.round(1000 / targetFps);

  for (let i = 0; i < frames.length; i += step) {
    const idx = Math.min(Math.round(i), frames.length - 1);
    result.push({ rgba: frames[idx].rgba, delay: newDelay });
  }
  return result;
}

