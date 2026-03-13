/**
 * Encode RGBA frames into animated GIF using gifenc.
 * Handles color quantization (256 palette), frame optimization,
 * and shared crop/resize pipeline from encoder.ts.
 */

import type { DecodedFrame, ConvertOptions, ConvertProgress } from "./types";
import { cropFrame, resizeFrame, subsampleFrames } from "./encoder";

/** Lazy-loaded gifenc module */
let gifencModule: typeof import("gifenc") | null = null;

async function getGifenc() {
  if (!gifencModule) {
    gifencModule = await import("gifenc");
  }
  return gifencModule;
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

  // Crop all frames if crop is set
  let outW = width;
  let outH = height;
  if (options.crop) {
    outW = Math.max(1, Math.round(options.crop.w * width));
    outH = Math.max(1, Math.round(options.crop.h * height));
    workFrames = workFrames.map((frame, i) => {
      onProgress?.({
        phase: "cropping",
        percent: Math.round((i / workFrames.length) * 100),
        frame: i + 1,
        total: workFrames.length,
      });
      const cropped = cropFrame(frame.rgba, width, height, options.crop!);
      return { rgba: cropped.data, delay: frame.delay };
    });
  }

  // Resize all frames if maxDimension is set
  if (options.maxDimension > 0 && (outW > options.maxDimension || outH > options.maxDimension)) {
    const scale = Math.min(options.maxDimension / outW, options.maxDimension / outH);
    const newW = Math.round(outW * scale);
    const newH = Math.round(outH * scale);

    workFrames = workFrames.map((frame, i) => {
      onProgress?.({
        phase: "resizing",
        percent: Math.round((i / workFrames.length) * 100),
        frame: i + 1,
        total: workFrames.length,
      });
      const resized = resizeFrame(frame.rgba, outW, outH, options.maxDimension);
      return { rgba: resized.data, delay: frame.delay };
    });

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

    // Quantize RGBA → 256-color palette using rgb444 format for better color matching
    const palette = quantize(frame.rgba, maxColors, { format: "rgb444" });
    const index = applyPalette(frame.rgba, palette, "rgb444");

    gif.writeFrame(index, outW, outH, {
      palette,
      delay: frame.delay,
      // GIF spec: 0 = loop forever, positive = loop N times
      repeat: i === 0 ? (options.loops === 0 ? 0 : options.loops) : undefined,
    });
  }

  gif.finish();

  onProgress?.({
    phase: "encoding",
    percent: 100,
    frame: workFrames.length,
    total: workFrames.length,
  });

  return new Blob([gif.bytesView()], { type: "image/gif" });
}
