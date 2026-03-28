/**
 * Client-side WebP RIFF parser for the Inspect tool.
 * Extracts metadata from WebP files without relying on exifr (which has no WebP support).
 *
 * WebP format: RIFF container with chunks:
 *   - VP8  = lossy frame (first 10 bytes have dimensions)
 *   - VP8L = lossless frame (first 5 bytes have dimensions + alpha flag)
 *   - VP8X = extended format header (features bitmask + canvas size)
 *   - ANIM = animation parameters (bgcolor, loop count)
 *   - ANMF = single animation frame (offset, size, duration, flags)
 *   - EXIF = embedded EXIF data (TIFF format, parseable by exifr)
 *   - XMP  = embedded XMP metadata
 *   - ICCP = ICC color profile
 *   - ALPH = alpha channel data
 */

export interface WebPMetadata {
  /** File format description */
  format: string;
  /** Canvas width in pixels */
  width: number;
  /** Canvas height in pixels */
  height: number;
  /** Whether the file is animated */
  isAnimated: boolean;
  /** Number of animation frames (1 for stills) */
  frameCount: number;
  /** Animation loop count (0 = infinite) */
  loopCount: number;
  /** Total animation duration in ms */
  totalDurationMs: number;
  /** Calculated FPS for animated WebP */
  fps: number;
  /** Has alpha transparency */
  hasAlpha: boolean;
  /** Lossy or lossless compression */
  compression: "lossy" | "lossless" | "mixed" | "unknown";
  /** Feature flags from VP8X chunk */
  features: string[];
  /** File size in bytes */
  fileSize: number;
  /** Individual frame durations (for animated WebP) */
  frameDurations: number[];
  /** Embedded EXIF data (raw bytes, can be passed to exifr) */
  exifBytes: Uint8Array | null;
  /** Embedded XMP data as string */
  xmpString: string | null;
  /** Has ICC color profile */
  hasICC: boolean;
}

/** Read a 4-byte ASCII string from a DataView */
function readFourCC(dv: DataView, offset: number): string {
  return String.fromCharCode(
    dv.getUint8(offset),
    dv.getUint8(offset + 1),
    dv.getUint8(offset + 2),
    dv.getUint8(offset + 3),
  );
}

/**
 * Parse a WebP file and extract all available metadata.
 * Works with both simple (VP8/VP8L) and extended (VP8X) WebP formats.
 */
export function parseWebP(buffer: ArrayBuffer): WebPMetadata {
  const dv = new DataView(buffer);
  const fileSize = buffer.byteLength;

  // Validate RIFF header
  if (fileSize < 12) throw new Error("File too small to be WebP");
  const riff = readFourCC(dv, 0);
  const webp = readFourCC(dv, 8);
  if (riff !== "RIFF" || webp !== "WEBP") {
    throw new Error("Not a valid WebP file (missing RIFF/WEBP header)");
  }

  const result: WebPMetadata = {
    format: "WebP",
    width: 0,
    height: 0,
    isAnimated: false,
    frameCount: 0,
    loopCount: 0,
    totalDurationMs: 0,
    fps: 0,
    hasAlpha: false,
    compression: "unknown",
    features: [],
    fileSize,
    frameDurations: [],
    exifBytes: null,
    xmpString: null,
    hasICC: false,
  };

  // Walk RIFF chunks starting after "RIFF" + size + "WEBP" (offset 12)
  let offset = 12;
  let hasVP8X = false;
  let hasLossy = false;
  let hasLossless = false;

  while (offset + 8 <= fileSize) {
    const chunkId = readFourCC(dv, offset);
    const chunkSize = dv.getUint32(offset + 4, true); // little-endian
    const dataStart = offset + 8;

    // Chunks are padded to even byte boundaries
    const nextChunk = dataStart + chunkSize + (chunkSize % 2);

    switch (chunkId) {
      case "VP8X": {
        // Extended format header: 10 bytes
        // Byte 0: feature flags (bits 0-5)
        // Bytes 4-6: canvas width - 1 (24-bit LE)
        // Bytes 7-9: canvas height - 1 (24-bit LE)
        hasVP8X = true;
        if (chunkSize >= 10) {
          const flags = dv.getUint8(dataStart);
          result.hasAlpha = !!(flags & 0x10);
          result.isAnimated = !!(flags & 0x02);
          result.hasICC = !!(flags & 0x20);
          const hasExif = !!(flags & 0x08);
          const hasXmp = !!(flags & 0x04);

          if (result.hasAlpha) result.features.push("Alpha");
          if (result.isAnimated) result.features.push("Animation");
          if (result.hasICC) result.features.push("ICC Profile");
          if (hasExif) result.features.push("EXIF");
          if (hasXmp) result.features.push("XMP");

          // Canvas dimensions (24-bit LE + 1)
          result.width =
            (dv.getUint8(dataStart + 4) |
              (dv.getUint8(dataStart + 5) << 8) |
              (dv.getUint8(dataStart + 6) << 16)) + 1;
          result.height =
            (dv.getUint8(dataStart + 7) |
              (dv.getUint8(dataStart + 8) << 8) |
              (dv.getUint8(dataStart + 9) << 16)) + 1;
        }
        break;
      }

      case "VP8 ": {
        // Simple lossy format — dimensions in first 10 bytes of bitstream
        hasLossy = true;
        if (!hasVP8X && chunkSize >= 10) {
          // VP8 bitstream: 3-byte frame tag, then 3-byte "sync code" 0x9D 0x01 0x2A,
          // then 2-byte width (LE, lower 14 bits), 2-byte height (LE, lower 14 bits)
          const b3 = dv.getUint8(dataStart + 3);
          const b4 = dv.getUint8(dataStart + 4);
          const b5 = dv.getUint8(dataStart + 5);
          if (b3 === 0x9d && b4 === 0x01 && b5 === 0x2a) {
            result.width = dv.getUint16(dataStart + 6, true) & 0x3fff;
            result.height = dv.getUint16(dataStart + 8, true) & 0x3fff;
          }
        }
        break;
      }

      case "VP8L": {
        // Simple lossless format — dimensions in first 5 bytes
        hasLossless = true;
        if (!hasVP8X && chunkSize >= 5) {
          // VP8L signature byte (0x2f), then 4 bytes:
          // bits 0-13 = width - 1, bits 14-27 = height - 1, bit 28 = alpha
          const sig = dv.getUint8(dataStart);
          if (sig === 0x2f) {
            const bits = dv.getUint32(dataStart + 1, true);
            result.width = (bits & 0x3fff) + 1;
            result.height = ((bits >> 14) & 0x3fff) + 1;
            result.hasAlpha = !!((bits >> 28) & 1);
          }
        }
        break;
      }

      case "ANIM": {
        // Animation parameters: 6 bytes
        // Bytes 0-3: background color (BGRA)
        // Bytes 4-5: loop count (0 = infinite)
        if (chunkSize >= 6) {
          result.loopCount = dv.getUint16(dataStart + 4, true);
        }
        break;
      }

      case "ANMF": {
        // Animation frame: at least 16 bytes header
        // Bytes 12-14: duration (24-bit LE) in ms
        result.frameCount++;
        if (chunkSize >= 16) {
          const duration =
            dv.getUint8(dataStart + 12) |
            (dv.getUint8(dataStart + 13) << 8) |
            (dv.getUint8(dataStart + 14) << 16);
          result.frameDurations.push(duration);
          result.totalDurationMs += duration;

          // Check sub-chunk type for lossy/lossless mix detection
          if (chunkSize >= 24) {
            const subChunkId = readFourCC(dv, dataStart + 16);
            if (subChunkId === "VP8 ") hasLossy = true;
            else if (subChunkId === "VP8L") hasLossless = true;
          }
        }
        break;
      }

      case "EXIF": {
        // Embedded EXIF data (TIFF format)
        if (chunkSize > 0) {
          result.exifBytes = new Uint8Array(buffer, dataStart, chunkSize);
        }
        break;
      }

      case "XMP ": {
        // Embedded XMP data (UTF-8 XML)
        if (chunkSize > 0) {
          const decoder = new TextDecoder("utf-8");
          result.xmpString = decoder.decode(
            new Uint8Array(buffer, dataStart, chunkSize),
          );
        }
        break;
      }

      // ICCP, ALPH — we just note their presence (already tracked via VP8X flags)
    }

    offset = nextChunk;
    // Safety: bail if we're stuck or past end
    if (nextChunk <= offset - 1 && nextChunk !== offset) break;
  }

  // If no ANMF frames found but not animated, it's a still image
  if (result.frameCount === 0) {
    result.frameCount = 1;
  }

  // Determine compression type
  if (hasLossy && hasLossless) {
    result.compression = "mixed";
  } else if (hasLossless) {
    result.compression = "lossless";
  } else if (hasLossy) {
    result.compression = "lossy";
  }

  // Calculate FPS for animated WebP
  if (result.isAnimated && result.totalDurationMs > 0 && result.frameCount > 1) {
    result.fps = Math.round(
      (result.frameCount / (result.totalDurationMs / 1000)) * 100,
    ) / 100;
  }

  // Format description
  if (result.isAnimated) {
    result.format = `Animated WebP (${result.compression})`;
  } else {
    result.format = `WebP (${result.compression})`;
  }

  return result;
}

/**
 * Convert WebPMetadata into a flat key-value record suitable for display
 * in the Inspect tool's metadata table.
 */
export function webpMetadataToEntries(meta: WebPMetadata): Record<string, string> {
  const entries: Record<string, string> = {};

  entries["Format"] = meta.format;
  entries["Dimensions"] = `${meta.width} × ${meta.height}`;
  entries["File Size"] = formatBytes(meta.fileSize);
  entries["Compression"] = meta.compression;
  entries["Has Alpha"] = meta.hasAlpha ? "Yes" : "No";

  if (meta.isAnimated) {
    entries["Animated"] = "Yes";
    entries["Frame Count"] = String(meta.frameCount);
    entries["Loop Count"] = meta.loopCount === 0 ? "Infinite" : String(meta.loopCount);
    entries["Total Duration"] = `${(meta.totalDurationMs / 1000).toFixed(2)}s`;
    entries["FPS"] = String(meta.fps);

    // Frame duration stats
    if (meta.frameDurations.length > 0) {
      const min = Math.min(...meta.frameDurations);
      const max = Math.max(...meta.frameDurations);
      const avg = Math.round(meta.totalDurationMs / meta.frameDurations.length);
      if (min === max) {
        entries["Frame Delay"] = `${min}ms (constant)`;
      } else {
        entries["Frame Delay"] = `${min}–${max}ms (avg ${avg}ms)`;
      }
    }
  }

  if (meta.features.length > 0) {
    entries["Features"] = meta.features.join(", ");
  }
  if (meta.hasICC) {
    entries["Color Profile"] = "ICC embedded";
  }
  if (meta.xmpString) {
    entries["XMP"] = `${meta.xmpString.length} bytes`;
  }

  return entries;
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}
