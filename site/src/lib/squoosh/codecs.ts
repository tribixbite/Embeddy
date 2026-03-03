/**
 * Lazy-load @jsquash WASM image codecs via dynamic import.
 * Each codec is ~100KB and loaded only when needed.
 */

type ImageData = globalThis.ImageData;

/** Decode functions return ImageData */
export interface Decoder {
  decode: (buffer: ArrayBuffer) => Promise<ImageData>;
}

/** Encode functions accept ImageData and return ArrayBuffer */
export interface Encoder {
  encode: (data: ImageData, options?: Record<string, unknown>) => Promise<ArrayBuffer>;
}

/** Supported output formats */
export type OutputFormat = "webp" | "jpeg" | "png" | "avif";

/** Cache loaded codec modules to avoid re-importing */
const decoderCache = new Map<string, Decoder>();
const encoderCache = new Map<string, Encoder>();

/**
 * Get a decoder for the given MIME type.
 * Supported: image/jpeg, image/png, image/webp, image/avif
 */
export async function getDecoder(mimeType: string): Promise<Decoder> {
  const key = mimeType.split("/")[1] ?? mimeType;
  if (decoderCache.has(key)) return decoderCache.get(key)!;

  let mod: Decoder;
  switch (key) {
    case "jpeg":
      mod = await import("@jsquash/jpeg");
      break;
    case "png":
      mod = await import("@jsquash/png");
      break;
    case "webp":
      mod = await import("@jsquash/webp");
      break;
    case "avif":
      mod = await import("@jsquash/avif");
      break;
    default:
      throw new Error(`Unsupported input format: ${mimeType}`);
  }

  decoderCache.set(key, mod);
  return mod;
}

/**
 * Get an encoder for the target output format.
 */
export async function getEncoder(format: OutputFormat): Promise<Encoder> {
  if (encoderCache.has(format)) return encoderCache.get(format)!;

  let mod: Encoder;
  switch (format) {
    case "jpeg":
      mod = await import("@jsquash/jpeg");
      break;
    case "png":
      mod = await import("@jsquash/png");
      break;
    case "webp":
      mod = await import("@jsquash/webp");
      break;
    case "avif":
      mod = await import("@jsquash/avif");
      break;
    default:
      throw new Error(`Unsupported output format: ${format}`);
  }

  encoderCache.set(format, mod);
  return mod;
}
