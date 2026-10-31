/**
 * Frame-by-frame GIF writing on top of gifenc: palette quantisation,
 * transparency handling and loop extension. DOM-free, so it runs both inside
 * the GIF Worker (gif-encoder.worker.ts, off the main thread — P5) and
 * in-thread where Workers are unavailable (bun tests).
 */

type Gifenc = typeof import("gifenc");

/** Alpha values at or below this are treated as transparent (matches gifenc's
 *  oneBitAlpha default threshold of 127). */
const ALPHA_THRESHOLD = 127;

/** True when any pixel in the RGBA buffer is transparent under the 1-bit model. */
export function frameHasTransparency(rgba: Uint8Array): boolean {
  for (let i = 3; i < rgba.length; i += 4) {
    if (rgba[i]! <= ALPHA_THRESHOLD) return true;
  }
  return false;
}

/** Map quality 1-100 to a max palette size of 16-256 colours. */
export function qualityToMaxColors(quality: number): number {
  return Math.max(16, Math.min(256, Math.round((quality / 100) * 256)));
}

/**
 * gifenc repeat semantics: -1 = play once (no Netscape ext), 0 = loop
 * forever, N = Netscape loop count. Our loops field uses 0 = forever /
 * N = play N times, so 1 maps to the play-once form.
 */
export function loopsToRepeat(loops: number): number {
  return loops === 1 ? -1 : loops;
}

/** Settings fixed for the whole animation */
export interface GifWriterOptions {
  maxColors: number;
  loops: number;
}

/** Incremental GIF writer */
export interface GifWriter {
  /** Quantise and append one frame */
  write(rgba: Uint8Array, width: number, height: number, delay: number): void;
  /** Finish the stream and return the exact encoded bytes */
  finish(): Uint8Array;
}

/** Create a writer over an already-loaded gifenc module. */
export function createGifWriter(gifenc: Gifenc, opts: GifWriterOptions): GifWriter {
  const { GIFEncoder, quantize, applyPalette } = gifenc;
  const gif = GIFEncoder();
  let index = 0;

  return {
    write(rgba, width, height, delay) {
      const repeat = index === 0 ? loopsToRepeat(opts.loops) : undefined;
      index++;

      if (frameHasTransparency(rgba)) {
        // Transparent frame: quantize with alpha so fully transparent pixels
        // get a dedicated palette entry instead of collapsing to opaque black.
        const palette = quantize(rgba, opts.maxColors, { format: "rgba4444", oneBitAlpha: true });
        const indexed = applyPalette(rgba, palette, "rgba4444");
        const transparentIndex = palette.findIndex((color) => color[3] === 0);
        gif.writeFrame(indexed, width, height, {
          palette,
          delay,
          repeat,
          transparent: true,
          transparentIndex: Math.max(0, transparentIndex),
          // Restore-to-background so transparent regions don't smear across frames
          dispose: 2,
        });
      } else {
        // Opaque frame: rgb444 gives better color matching than rgba4444
        const palette = quantize(rgba, opts.maxColors, { format: "rgb444" });
        const indexed = applyPalette(rgba, palette, "rgb444");
        gif.writeFrame(indexed, width, height, { palette, delay, repeat });
      }
    },
    finish() {
      gif.finish();
      // bytesView() is a subarray of gifenc's capacity-doubling buffer —
      // slice to exactly the written bytes so no trailing junk ships (M14)
      return gif.bytesView().slice();
    },
  };
}
