/**
 * Encode RGBA frames into animated WebP using wasm-webp.
 * Handles resizing via OffscreenCanvas before encoding.
 */

import type { DecodedFrame, ConvertOptions, ConvertProgress } from "./types";

/** Lazy-loaded wasm-webp module */
let wasmModule: typeof import("wasm-webp") | null = null;

async function getModule() {
  if (!wasmModule) {
    wasmModule = await import("wasm-webp");
  }
  return wasmModule;
}

/**
 * Resize a single RGBA frame to fit within maxDimension, preserving aspect ratio.
 * Returns the resized RGBA data and new dimensions.
 */
function resizeFrame(
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

function createCanvas(w: number, h: number): HTMLCanvasElement {
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  return c;
}

/**
 * Subsample frames to match a target FPS (for video sources where
 * the decoded FPS may differ from the desired output FPS).
 */
function subsampleFrames(
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

/**
 * Encode decoded frames into an animated WebP blob.
 * @param frames - RGBA frames with per-frame delays
 * @param width - Source canvas width
 * @param height - Source canvas height
 * @param options - Encoding options (quality, lossless, maxDimension, loops, targetFps)
 * @param sourceFps - Original source FPS (for subsampling calculation)
 * @param onProgress - Progress callback
 */
export async function encodeAnimatedWebP(
  frames: DecodedFrame[],
  width: number,
  height: number,
  options: ConvertOptions,
  sourceFps: number,
  onProgress?: (p: ConvertProgress) => void,
): Promise<Blob> {
  const wasm = await getModule();

  // Subsample if target FPS is lower than source
  let workFrames = subsampleFrames(frames, sourceFps, options.targetFps);

  // Resize all frames if maxDimension is set
  let outW = width;
  let outH = height;
  if (options.maxDimension > 0 && (width > options.maxDimension || height > options.maxDimension)) {
    const scale = Math.min(options.maxDimension / width, options.maxDimension / height);
    outW = Math.round(width * scale);
    outH = Math.round(height * scale);

    workFrames = workFrames.map((frame, i) => {
      onProgress?.({
        phase: "resizing",
        percent: Math.round((i / workFrames.length) * 100),
        frame: i + 1,
        total: workFrames.length,
      });
      const resized = resizeFrame(frame.rgba, width, height, options.maxDimension);
      return { rgba: resized.data, delay: frame.delay };
    });
  }

  // Build wasm-webp frame array
  onProgress?.({ phase: "encoding", percent: 0, frame: 0, total: workFrames.length });

  const wasmFrames = workFrames.map((frame) => ({
    data: frame.rgba,
    duration: frame.delay,
    config: {
      lossless: options.lossless ? 1 : 0,
      quality: options.quality,
    },
  }));

  const result = await wasm.encodeAnimation(outW, outH, true, wasmFrames);

  onProgress?.({ phase: "encoding", percent: 100, frame: workFrames.length, total: workFrames.length });

  if (!result) {
    throw new Error("WebP encoding failed — encodeAnimation returned null");
  }

  return new Blob([result.buffer as ArrayBuffer], { type: "image/webp" });
}
