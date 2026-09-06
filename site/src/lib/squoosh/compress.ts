/**
 * Full image compression pipeline:
 * 1. Decode source image to ImageData (via @jsquash decoder)
 * 2. Resize if maxDimension is set (via OffscreenCanvas)
 * 3. Encode to target format (via @jsquash encoder)
 */

import { getDecoder, getEncoder, type OutputFormat } from "./codecs";

export interface CompressOptions {
  format: OutputFormat;
  quality: number;        // 1-100, ignored for lossless/PNG
  maxDimension: number;   // 0 = no resize, otherwise max width or height
  lossless: boolean;
}

/**
 * Resize ImageData to fit within maxDimension, preserving aspect ratio.
 * Uses OffscreenCanvas for high-quality downscaling.
 */
function resizeImageData(data: ImageData, maxDim: number): ImageData {
  const { width, height } = data;
  if (maxDim <= 0 || (width <= maxDim && height <= maxDim)) {
    return data;
  }

  const scale = maxDim / Math.max(width, height);
  const newW = Math.round(width * scale);
  const newH = Math.round(height * scale);

  // Draw source ImageData onto a canvas, then read back at new size
  let srcCanvas = new OffscreenCanvas(width, height);
  const srcCtx = srcCanvas.getContext("2d")!;
  srcCtx.putImageData(data, 0, 0);

  // Even "high" smoothing samples a limited kernel, so a single >2x jump
  // still aliases — step down by halves first, then do the final resize.
  let curW = width;
  let curH = height;
  while (curW / 2 >= newW && curH / 2 >= newH && curW > 2 && curH > 2) {
    const halfW = Math.max(newW, Math.floor(curW / 2));
    const halfH = Math.max(newH, Math.floor(curH / 2));
    const stepCanvas = new OffscreenCanvas(halfW, halfH);
    const stepCtx = stepCanvas.getContext("2d")!;
    stepCtx.imageSmoothingEnabled = true;
    stepCtx.imageSmoothingQuality = "high";
    stepCtx.drawImage(srcCanvas, 0, 0, curW, curH, 0, 0, halfW, halfH);
    srcCanvas = stepCanvas;
    curW = halfW;
    curH = halfH;
  }

  const dstCanvas = new OffscreenCanvas(newW, newH);
  const dstCtx = dstCanvas.getContext("2d")!;
  dstCtx.imageSmoothingEnabled = true;
  dstCtx.imageSmoothingQuality = "high";
  dstCtx.drawImage(srcCanvas, 0, 0, curW, curH, 0, 0, newW, newH);

  return dstCtx.getImageData(0, 0, newW, newH);
}

/**
 * Detect MIME type from the first few bytes of a buffer (magic bytes).
 * Fallback to the File's reported MIME type.
 */
export function detectMimeType(buffer: ArrayBuffer, fallback: string): string {
  const view = new Uint8Array(buffer.slice(0, 12));

  // JPEG: FF D8 FF
  if (view[0] === 0xFF && view[1] === 0xD8 && view[2] === 0xFF) return "image/jpeg";
  // PNG: 89 50 4E 47
  if (view[0] === 0x89 && view[1] === 0x50 && view[2] === 0x4E && view[3] === 0x47) return "image/png";
  // WebP: RIFF....WEBP
  if (view[0] === 0x52 && view[1] === 0x49 && view[2] === 0x46 && view[3] === 0x46 &&
      view[8] === 0x57 && view[9] === 0x45 && view[10] === 0x42 && view[11] === 0x50) return "image/webp";
  // AVIF: ....ftypavif or ....ftypavis — "ftyp" alone is any ISO-BMFF file
  // (HEIC, MP4, ...), so the major brand at bytes 8-11 must match too
  if (view[4] === 0x66 && view[5] === 0x74 && view[6] === 0x79 && view[7] === 0x70) {
    const brand = String.fromCharCode(view[8]!, view[9]!, view[10]!, view[11]!);
    if (brand === "avif" || brand === "avis") return "image/avif";
    return fallback;
  }

  return fallback;
}

/**
 * Map CompressOptions to the encoder-specific options object for the
 * @jsquash codec of the chosen format. Pure — unit-tested in compress.test.ts.
 */
export function buildEncodeOptions(
  opts: Pick<CompressOptions, "format" | "quality" | "lossless">,
): Record<string, unknown> {
  const encodeOptions: Record<string, unknown> = {};

  if (opts.format === "webp") {
    encodeOptions.quality = opts.lossless ? 100 : opts.quality;
    encodeOptions.lossless = opts.lossless ? 1 : 0;
  } else if (opts.format === "jpeg") {
    encodeOptions.quality = opts.quality;
  } else if (opts.format === "avif") {
    // AVIF quality maps to cqLevel (0=best, 63=worst)
    // quality 1-100 → cqLevel 62-0
    if (opts.lossless) {
      // @jsquash/avif EncodeOptions has no `lossless` field (embind drops
      // unknown keys) — lossless AVIF is cqLevel 0 for color and alpha with
      // chroma subsampling disabled (subsample 3 = YUV 4:4:4)
      encodeOptions.cqLevel = 0;
      encodeOptions.cqAlphaLevel = 0;
      encodeOptions.subsample = 3;
    } else {
      encodeOptions.cqLevel = Math.round(62 - (opts.quality / 100) * 62);
    }
  }
  // PNG: no quality options, always lossless

  return encodeOptions;
}

/**
 * Compress a source image File to the target format with given options.
 * Returns a Blob of the compressed image.
 */
export async function compress(file: File, opts: CompressOptions): Promise<Blob> {
  const buffer = await file.arrayBuffer();
  const mimeType = detectMimeType(buffer, file.type);

  // Decode
  const decoder = await getDecoder(mimeType);
  let imageData = await decoder.decode(buffer);

  // Resize if needed
  if (opts.maxDimension > 0) {
    imageData = resizeImageData(imageData, opts.maxDimension);
  }

  // Encode
  const encoder = await getEncoder(opts.format);
  const encoded = await encoder.encode(imageData, buildEncodeOptions(opts));
  const ext = opts.format === "jpeg" ? "jpeg" : opts.format;
  return new Blob([encoded], { type: `image/${ext}` });
}
