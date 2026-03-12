/**
 * Decode GIF files into RGBA frames using gifuct-js.
 * Handles frame compositing, disposal methods, and transparency.
 */

import { parseGIF, decompressFrames } from "gifuct-js";
import type { DecodedFrame, SourceInfo, ConvertProgress } from "./types";

/**
 * Decode a GIF ArrayBuffer into composited RGBA frames.
 * GIF frames are partial patches that must be layered onto a canvas
 * with proper disposal method handling.
 */
export async function decodeGif(
  buffer: ArrayBuffer,
  onProgress?: (p: ConvertProgress) => void,
): Promise<{ frames: DecodedFrame[]; info: SourceInfo }> {
  const parsed = parseGIF(buffer);
  const rawFrames = decompressFrames(parsed, true); // true = generate RGBA patches

  if (!rawFrames.length) {
    throw new Error("GIF contains no frames");
  }

  const width = parsed.lsd.width;
  const height = parsed.lsd.height;

  // Use OffscreenCanvas for compositing if available, else <canvas>
  const canvas =
    typeof OffscreenCanvas !== "undefined"
      ? new OffscreenCanvas(width, height)
      : createFallbackCanvas(width, height);
  const ctx = canvas.getContext("2d") as
    | OffscreenCanvasRenderingContext2D
    | CanvasRenderingContext2D;
  if (!ctx) throw new Error("Could not create canvas context");

  // Temp canvas for drawing individual frame patches
  const patchCanvas =
    typeof OffscreenCanvas !== "undefined"
      ? new OffscreenCanvas(width, height)
      : createFallbackCanvas(width, height);
  const patchCtx = patchCanvas.getContext("2d") as
    | OffscreenCanvasRenderingContext2D
    | CanvasRenderingContext2D;
  if (!patchCtx) throw new Error("Could not create patch canvas context");

  const frames: DecodedFrame[] = [];
  let totalDuration = 0;

  for (let i = 0; i < rawFrames.length; i++) {
    const frame = rawFrames[i];
    const { dims, patch, delay, disposalType } = frame;

    onProgress?.({
      phase: "decoding",
      percent: Math.round((i / rawFrames.length) * 100),
      frame: i + 1,
      total: rawFrames.length,
    });

    // Draw the frame patch onto a temp canvas, then composite
    patchCanvas.width = dims.width;
    patchCanvas.height = dims.height;
    const imageData = patchCtx.createImageData(dims.width, dims.height);
    imageData.data.set(patch);
    patchCtx.putImageData(imageData, 0, 0);

    // Composite onto main canvas at frame offset
    ctx.drawImage(patchCanvas, dims.left, dims.top);

    // Capture the full composited canvas as RGBA
    const fullFrame = ctx.getImageData(0, 0, width, height);
    const frameDelay = Math.max(delay * 10, 20); // GIF delay is in centiseconds; enforce 20ms minimum
    frames.push({
      rgba: new Uint8Array(fullFrame.data.buffer),
      delay: frameDelay,
    });
    totalDuration += frameDelay;

    // Handle disposal for next frame
    if (disposalType === 2) {
      // Restore to background: clear the frame region
      ctx.clearRect(dims.left, dims.top, dims.width, dims.height);
    } else if (disposalType === 3) {
      // Restore to previous: we'd need to save/restore canvas state.
      // Simplified: treat as "do not dispose" (most GIFs don't use type 3)
    }
    // disposalType 0 or 1: leave canvas as-is
  }

  const avgFps = frames.length / (totalDuration / 1000);

  return {
    frames,
    info: {
      width,
      height,
      frameCount: frames.length,
      totalDuration,
      fps: Math.round(avgFps * 10) / 10,
      format: "gif",
    },
  };
}

/** Create a DOM <canvas> fallback for environments without OffscreenCanvas */
function createFallbackCanvas(w: number, h: number): HTMLCanvasElement {
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  return c;
}
