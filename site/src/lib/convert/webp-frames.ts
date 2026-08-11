/**
 * Split an animated WebP into standalone still-image WebPs, one per frame.
 *
 * Why this exists: Firefox ships no `ImageDecoder`, so the only previous route
 * for animated WebP was pointing an `<img>` at the file and sampling it on a
 * timer — which drops and duplicates frames whenever the tab is throttled.
 *
 * An animated WebP is just a RIFF container of ANMF chunks, each holding an
 * ordinary VP8/VP8L bitstream. Re-wrapping that bitstream in a minimal
 * `RIFF….WEBP` header produces a still image every browser can decode with
 * `createImageBitmap`, so frames come out exactly as authored — no timing games.
 *
 * Container reference: https://developers.google.com/speed/webp/docs/riff_container
 */

/** How a frame is combined with the canvas beneath it. */
export type BlendMethod = "blend" | "replace";

/** What happens to the frame's region before the next frame is drawn. */
export type DisposeMethod = "none" | "background";

/** One animation frame, re-wrapped as an independently decodable WebP. */
export interface WebPFrame {
  /** A complete still WebP file containing just this frame's pixels */
  bytes: Uint8Array;
  /** Frame offset within the canvas */
  x: number;
  y: number;
  /** Frame dimensions (may be smaller than the canvas) */
  width: number;
  height: number;
  /** Display duration in milliseconds */
  duration: number;
  blend: BlendMethod;
  dispose: DisposeMethod;
}

/** Result of splitting an animated WebP container. */
export interface WebPFrameSet {
  /** Canvas dimensions from the VP8X header */
  width: number;
  height: number;
  /** Loop count (0 = forever) */
  loopCount: number;
  frames: WebPFrame[];
}

const RIFF = 0x52494646; // "RIFF"
const WEBP = 0x57454250; // "WEBP"

function fourCC(view: DataView, offset: number): string {
  return String.fromCharCode(
    view.getUint8(offset),
    view.getUint8(offset + 1),
    view.getUint8(offset + 2),
    view.getUint8(offset + 3),
  );
}

function readUint24(view: DataView, offset: number): number {
  return (
    view.getUint8(offset) |
    (view.getUint8(offset + 1) << 8) |
    (view.getUint8(offset + 2) << 16)
  );
}

function writeFourCC(bytes: Uint8Array, offset: number, id: string): void {
  for (let i = 0; i < 4; i++) bytes[offset + i] = id.charCodeAt(i);
}

function writeUint32LE(bytes: Uint8Array, offset: number, value: number): void {
  bytes[offset] = value & 0xff;
  bytes[offset + 1] = (value >>> 8) & 0xff;
  bytes[offset + 2] = (value >>> 16) & 0xff;
  bytes[offset + 3] = (value >>> 24) & 0xff;
}

function writeUint24LE(bytes: Uint8Array, offset: number, value: number): void {
  bytes[offset] = value & 0xff;
  bytes[offset + 1] = (value >>> 8) & 0xff;
  bytes[offset + 2] = (value >>> 16) & 0xff;
}

/** A chunk located inside a frame payload. */
interface SubChunk {
  id: string;
  /** Offset of the chunk header (not the payload) within the source buffer */
  start: number;
  /** Total bytes including header and any pad byte */
  totalSize: number;
}

/**
 * Build a standalone still WebP around a frame's image sub-chunks.
 *
 * A bare VP8/VP8L chunk is enough on its own. Lossy-with-alpha frames carry a
 * separate ALPH chunk, which is only meaningful alongside a VP8X header, so one
 * is synthesised with the alpha flag set.
 */
function buildStillWebP(
  source: Uint8Array,
  imageChunks: SubChunk[],
  width: number,
  height: number,
): Uint8Array {
  const hasAlphaChunk = imageChunks.some((c) => c.id === "ALPH");
  const vp8xSize = hasAlphaChunk ? 8 + 10 : 0;
  const payloadSize = imageChunks.reduce((sum, c) => sum + c.totalSize, 0);

  // "WEBP" + optional VP8X + image chunks
  const riffPayload = 4 + vp8xSize + payloadSize;
  const out = new Uint8Array(8 + riffPayload);

  writeFourCC(out, 0, "RIFF");
  writeUint32LE(out, 4, riffPayload);
  writeFourCC(out, 8, "WEBP");

  let cursor = 12;
  if (hasAlphaChunk) {
    writeFourCC(out, cursor, "VP8X");
    writeUint32LE(out, cursor + 4, 10);
    out[cursor + 8] = 0x10; // alpha flag
    // bytes 9-11 reserved, already zero
    writeUint24LE(out, cursor + 12, width - 1);
    writeUint24LE(out, cursor + 15, height - 1);
    cursor += 18;
  }

  for (const chunk of imageChunks) {
    out.set(source.subarray(chunk.start, chunk.start + chunk.totalSize), cursor);
    cursor += chunk.totalSize;
  }

  return out;
}

/**
 * Parse an animated WebP and return each frame as a standalone still WebP.
 *
 * Returns `null` when the file is not an animated WebP (no VP8X animation flag
 * or no ANMF chunks), so callers can fall back to still-image handling.
 */
export function splitAnimatedWebP(buffer: ArrayBuffer): WebPFrameSet | null {
  const size = buffer.byteLength;
  if (size < 16) return null;

  const view = new DataView(buffer);
  const source = new Uint8Array(buffer);
  if (view.getUint32(0) !== RIFF || view.getUint32(8) !== WEBP) return null;

  let canvasWidth = 0;
  let canvasHeight = 0;
  let loopCount = 0;
  let isAnimated = false;
  const frames: WebPFrame[] = [];

  let offset = 12;
  while (offset + 8 <= size) {
    const id = fourCC(view, offset);
    const declaredSize = view.getUint32(offset + 4, true);
    const dataStart = offset + 8;
    // Clamp so a truncated or hostile file can't read past the buffer
    const chunkSize = Math.min(declaredSize, size - dataStart);
    const next = dataStart + declaredSize + (declaredSize % 2);

    if (id === "VP8X" && chunkSize >= 10) {
      isAnimated = (view.getUint8(dataStart) & 0x02) !== 0;
      canvasWidth = readUint24(view, dataStart + 4) + 1;
      canvasHeight = readUint24(view, dataStart + 7) + 1;
    } else if (id === "ANIM" && chunkSize >= 6) {
      loopCount = view.getUint16(dataStart + 4, true);
    } else if (id === "ANMF" && chunkSize >= 16) {
      // ANMF header: x/2, y/2, w-1, h-1 (3 bytes each), duration (3), flags (1)
      const x = readUint24(view, dataStart) * 2;
      const y = readUint24(view, dataStart + 3) * 2;
      const width = readUint24(view, dataStart + 6) + 1;
      const height = readUint24(view, dataStart + 9) + 1;
      const duration = readUint24(view, dataStart + 12);
      const flags = view.getUint8(dataStart + 15);

      // Spec bit layout: 6 reserved bits, then blending (B), then disposal (D)
      const blend: BlendMethod = (flags >> 1) & 1 ? "replace" : "blend";
      const dispose: DisposeMethod = flags & 1 ? "background" : "none";

      // Walk the frame's own sub-chunks (ALPH and/or VP8 /VP8L)
      const imageChunks: SubChunk[] = [];
      const frameEnd = dataStart + chunkSize;
      let sub = dataStart + 16;
      while (sub + 8 <= frameEnd) {
        const subId = fourCC(view, sub);
        const subSize = view.getUint32(sub + 4, true);
        const padded = subSize + (subSize % 2);
        if (sub + 8 + padded > frameEnd) break;
        if (subId === "ALPH" || subId === "VP8 " || subId === "VP8L") {
          imageChunks.push({ id: subId, start: sub, totalSize: 8 + padded });
        }
        sub += 8 + padded;
      }

      if (imageChunks.length > 0) {
        frames.push({
          bytes: buildStillWebP(source, imageChunks, width, height),
          x,
          y,
          width,
          height,
          duration,
          blend,
          dispose,
        });
      }
    }

    if (next <= offset) break; // malformed size that fails to advance
    offset = next;
  }

  if (!isAnimated || frames.length === 0) return null;
  if (canvasWidth <= 0 || canvasHeight <= 0) return null;

  return { width: canvasWidth, height: canvasHeight, loopCount, frames };
}
