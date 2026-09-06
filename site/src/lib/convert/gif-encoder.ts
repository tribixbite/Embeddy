/**
 * Encode RGBA frames into animated GIF using gifenc.
 * Handles color quantization (256 palette), frame optimization,
 * and shared crop/resize pipeline from encoder.ts.
 */

import type { DecodedFrame, ConvertOptions, ConvertProgress } from "./types";
import {
  cropFrame,
  cropRectToPixels,
  resizeFrame,
  subsampleFrames,
  YIELD_EVERY,
  yieldToEventLoop,
} from "./encoder";

/** Lazy-loaded gifenc module */
let gifencModule: typeof import("gifenc") | null = null;

async function getGifenc() {
  if (!gifencModule) {
    gifencModule = await import("gifenc");
  }
  return gifencModule;
}

/** Alpha values at or below this are treated as transparent (matches gifenc's
 *  oneBitAlpha default threshold of 127). */
const ALPHA_THRESHOLD = 127;

/** True when any pixel in the RGBA buffer is transparent under the 1-bit model. */
function frameHasTransparency(rgba: Uint8Array): boolean {
  for (let i = 3; i < rgba.length; i += 4) {
    if (rgba[i] <= ALPHA_THRESHOLD) return true;
  }
  return false;
}

/**
 * Encode decoded frames into an animated GIF blob.
 * Pipeline: subsample → crop → resize → quantize → encode
 * @param frames - RGBA frames with per-frame delays
 * @param width - Source canvas width
 * @param height - Source canvas height
 * @param options - Encoding options
 * @param sourceFps - Original source FPS (for subsampling)
 * @param onProgress - Progress callback
 */
export async function encodeAnimatedGif(
  frames: DecodedFrame[],
  width: number,
  height: number,
  options: ConvertOptions,
  sourceFps: number,
  onProgress?: (p: ConvertProgress) => void,
): Promise<Blob> {
  const { GIFEncoder, quantize, applyPalette } = await getGifenc();

  // Subsample if target FPS is lower than source
  let workFrames = subsampleFrames(frames, sourceFps, options.targetFps);

  // Crop all frames if crop is set. Output dims come from the same clamped
  // arithmetic cropFrame uses — recomputing them from the fraction alone
  // could disagree by a pixel and skew every row of the encoded GIF.
  let outW = width;
  let outH = height;
  if (options.crop) {
    const { pw, ph } = cropRectToPixels(width, height, options.crop);
    outW = pw;
    outH = ph;
    const cropped: DecodedFrame[] = [];
    for (let i = 0; i < workFrames.length; i++) {
      const frame = workFrames[i];
      onProgress?.({
        phase: "cropping",
        percent: Math.round((i / workFrames.length) * 100),
        frame: i + 1,
        total: workFrames.length,
      });
      const result = cropFrame(frame.rgba, width, height, options.crop);
      cropped.push({ rgba: result.data, delay: frame.delay });
      // Synchronous canvas work starves the renderer — let progress paint
      if (i % YIELD_EVERY === YIELD_EVERY - 1) await yieldToEventLoop();
    }
    workFrames = cropped;
  }

  // Resize all frames if maxDimension is set
  if (options.maxDimension > 0 && (outW > options.maxDimension || outH > options.maxDimension)) {
    const scale = Math.min(options.maxDimension / outW, options.maxDimension / outH);
    const newW = Math.round(outW * scale);
    const newH = Math.round(outH * scale);

    const resizedFrames: DecodedFrame[] = [];
    for (let i = 0; i < workFrames.length; i++) {
      const frame = workFrames[i];
      onProgress?.({
        phase: "resizing",
        percent: Math.round((i / workFrames.length) * 100),
        frame: i + 1,
        total: workFrames.length,
      });
      const resized = resizeFrame(frame.rgba, outW, outH, options.maxDimension);
      resizedFrames.push({ rgba: resized.data, delay: frame.delay });
      if (i % YIELD_EVERY === YIELD_EVERY - 1) await yieldToEventLoop();
    }
    workFrames = resizedFrames;

    outW = newW;
    outH = newH;
  }

  // Encode to GIF frame by frame
  const gif = GIFEncoder();

  // Map quality 1-100 to max palette colors 16-256
  // Higher quality = more colors = larger file but better fidelity
  const maxColors = Math.max(16, Math.round((options.quality / 100) * 256));

  for (let i = 0; i < workFrames.length; i++) {
    const frame = workFrames[i];

    onProgress?.({
      phase: "encoding",
      percent: Math.round((i / workFrames.length) * 100),
      frame: i + 1,
      total: workFrames.length,
    });

    // gifenc repeat semantics: -1 = play once (no Netscape ext),
    // 0 = loop forever, N = Netscape loop count. Our loops field uses
    // 0 = forever / N = play N times, so 1 maps to the play-once form.
    const repeat = i === 0 ? (options.loops === 1 ? -1 : options.loops) : undefined;

    if (frameHasTransparency(frame.rgba)) {
      // Transparent frame: quantize with alpha so fully transparent pixels
      // get a dedicated palette entry instead of collapsing to opaque black.
      const palette = quantize(frame.rgba, maxColors, {
        format: "rgba4444",
        oneBitAlpha: true,
      });
      const index = applyPalette(frame.rgba, palette, "rgba4444");
      const transparentIndex = palette.findIndex((color) => color[3] === 0);

      gif.writeFrame(index, outW, outH, {
        palette,
        delay: frame.delay,
        repeat,
        transparent: true,
        transparentIndex: Math.max(0, transparentIndex),
        // Restore-to-background so transparent regions don't smear across frames
        dispose: 2,
      });
    } else {
      // Opaque frame: rgb444 gives better color matching than rgba4444
      const palette = quantize(frame.rgba, maxColors, { format: "rgb444" });
      const index = applyPalette(frame.rgba, palette, "rgb444");

      gif.writeFrame(index, outW, outH, { palette, delay: frame.delay, repeat });
    }

    // Quantization is pure CPU work — yield so the progress bar can paint
    if (i % YIELD_EVERY === YIELD_EVERY - 1) await yieldToEventLoop();
  }

  gif.finish();

  onProgress?.({
    phase: "encoding",
    percent: 100,
    frame: workFrames.length,
    total: workFrames.length,
  });

  // bytesView() is a subarray of gifenc's capacity-doubling buffer; Blob
  // honors the view's byteOffset/byteLength, so no trailing junk is shipped.
  return new Blob([gif.bytesView()], { type: "image/gif" });
}
