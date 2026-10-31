/**
 * Encode RGBA frames into animated GIF using gifenc.
 * Handles color quantization (256 palette), frame optimization,
 * and shared crop/resize pipeline from encoder.ts.
 */

import type { DecodedFrame, ConvertOptions, ConvertProgress } from "./types";
import { subsampleFrames, YIELD_EVERY, yieldToEventLoop } from "./encoder";
import { FramePreparer, transformFromOptions } from "./prepare-frame";
import { throwIfAborted } from "./abort";

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
 * Pipeline per frame: subsample → crop/resize/rotate (one drawImage) →
 * quantize → write. Frames are transformed one at a time inside the write
 * loop, so no transformed copy of the whole animation is ever held (H2).
 * @param frames - RGBA frames with per-frame delays
 * @param width - Source canvas width
 * @param height - Source canvas height
 * @param options - Encoding options
 * @param sourceFps - Original source FPS (for subsampling)
 * @param onProgress - Progress callback
 * @param signal - Aborting rejects with an AbortError at the next frame (M7)
 */
export async function encodeAnimatedGif(
  frames: DecodedFrame[],
  width: number,
  height: number,
  options: ConvertOptions,
  sourceFps: number,
  onProgress?: (p: ConvertProgress) => void,
  signal?: AbortSignal,
): Promise<Blob> {
  throwIfAborted(signal);
  const { GIFEncoder, quantize, applyPalette } = await getGifenc();

  // Subsample if target FPS is lower than source (keeps references, no copy)
  const workFrames = subsampleFrames(frames, sourceFps, options.targetFps);
  const preparer = new FramePreparer(width, height, transformFromOptions(options));
  const { outW, outH } = preparer.plan;

  // Encode to GIF frame by frame
  const gif = GIFEncoder();

  // Map quality 1-100 to max palette colors 16-256
  // Higher quality = more colors = larger file but better fidelity
  const maxColors = Math.max(16, Math.round((options.quality / 100) * 256));

  for (let i = 0; i < workFrames.length; i++) {
    throwIfAborted(signal);
    const source = workFrames[i]!;
    const frame = { rgba: preparer.fromRgba(source.rgba).rgba, delay: source.delay };

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
