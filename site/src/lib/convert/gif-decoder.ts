/**
 * Decode GIF files into RGBA frames using gifuct-js.
 * Handles frame compositing, disposal methods, and transparency.
 */

import { parseGIF, decompressFrames } from "gifuct-js";
import type { DecodedFrame, SourceInfo, ConvertProgress } from "./types";

/** Same RGBA budget the video and WebP decoders use (512 MB). */
const MEMORY_BUDGET_BYTES = 512 * 1024 * 1024;

/** Hard ceiling regardless of frame size, matching the other decoders. */
const MAX_FRAMES = 1500;

/** Yield to the event loop every N frames so progress actually paints. */
const YIELD_EVERY = 24;

/** Hand control back to the browser so the progress bar can render. */
function yieldToEventLoop(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

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

  // Memory-aware cap. Without this a long high-resolution GIF (e.g. 1000 frames
  // at 1920x1080 = ~8 GB of RGBA) crashes the tab instead of decoding partially.
  const bytesPerFrame = width * height * 4;
  const memoryMaxFrames = Math.max(30, Math.floor(MEMORY_BUDGET_BYTES / bytesPerFrame));
  const frameLimit = Math.min(rawFrames.length, MAX_FRAMES, memoryMaxFrames);

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

  for (let i = 0; i < frameLimit; i++) {
    const frame = rawFrames[i];
    const { dims, patch, delay, disposalType } = frame;

    onProgress?.({
      phase: "decoding",
      percent: Math.round((i / frameLimit) * 100),
      frame: i + 1,
      total: frameLimit,
    });

    // Disposal 3 ("restore to previous") requires the pre-draw canvas contents,
    // so snapshot before compositing rather than treating it as "do not dispose".
    const previousState =
      disposalType === 3 ? ctx.getImageData(0, 0, width, height) : null;

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
    } else if (previousState) {
      // Restore to previous: put back the snapshot taken above
      ctx.putImageData(previousState, 0, 0);
    }
    // disposalType 0 or 1: leave canvas as-is

    // Decoding is otherwise fully synchronous, which starves the renderer and
    // freezes the progress bar for the whole decode.
    if (i % YIELD_EVERY === YIELD_EVERY - 1) await yieldToEventLoop();
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
      frameCapped: frameLimit < rawFrames.length,
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
