import { describe, expect, test } from "bun:test";
import { parseWebP } from "./webp-parser";

// ── Fixture helpers ─────────────────────────────────────────────────────────

function fourCC(id: string): number[] {
  return [...id].map((ch) => ch.charCodeAt(0));
}

function u32le(n: number): number[] {
  return [n & 0xff, (n >> 8) & 0xff, (n >> 16) & 0xff, (n >>> 24) & 0xff];
}

function u24le(n: number): number[] {
  return [n & 0xff, (n >> 8) & 0xff, (n >> 16) & 0xff];
}

/** Build a chunk with an optionally-lying declared size, plus RIFF padding. */
function chunk(id: string, payload: number[], declaredSize = payload.length): number[] {
  const bytes = [...fourCC(id), ...u32le(declaredSize), ...payload];
  if (payload.length % 2 === 1) bytes.push(0); // even-byte padding
  return bytes;
}

/** Wrap chunks in a RIFF/WEBP container. */
function riff(chunks: number[]): ArrayBuffer {
  const body = [...fourCC("WEBP"), ...chunks];
  const all = [...fourCC("RIFF"), ...u32le(body.length), ...body];
  return new Uint8Array(all).buffer;
}

/** VP8X extended header: flags byte + reserved + canvas dims (each minus one). */
function vp8x(flags: number, width: number, height: number): number[] {
  return chunk("VP8X", [
    flags, 0, 0, 0,
    ...u24le(width - 1),
    ...u24le(height - 1),
  ]);
}

/** ANMF animation frame with a duration, and enough bytes for sub-chunk sniffing. */
function anmf(durationMs: number, subChunk = "VP8 "): number[] {
  return chunk("ANMF", [
    ...u24le(0), ...u24le(0),          // frame x/y offset
    ...u24le(0), ...u24le(0),          // frame width/height
    ...u24le(durationMs),              // duration
    0,                                 // flags
    ...fourCC(subChunk),
    ...u32le(4), 0, 0, 0, 0,           // minimal sub-chunk payload
  ]);
}

// ── Tests ───────────────────────────────────────────────────────────────────

describe("parseWebP — container validation", () => {
  test("rejects a file that is too small to hold a header", () => {
    expect(() => parseWebP(new Uint8Array([0x52, 0x49]).buffer)).toThrow(
      /too small/i,
    );
  });

  test("rejects a non-WebP RIFF file", () => {
    const wav = new Uint8Array([
      ...fourCC("RIFF"), ...u32le(4), ...fourCC("WAVE"),
    ]).buffer;
    expect(() => parseWebP(wav)).toThrow(/not a valid webp/i);
  });
});

describe("parseWebP — still images", () => {
  test("reads dimensions from a simple lossy VP8 frame", () => {
    // 3-byte frame tag, 0x9d 0x01 0x2a sync code, then 14-bit width/height
    const buffer = riff(
      chunk("VP8 ", [
        0, 0, 0,
        0x9d, 0x01, 0x2a,
        320 & 0xff, (320 >> 8) & 0x3f,
        240 & 0xff, (240 >> 8) & 0x3f,
      ]),
    );
    const meta = parseWebP(buffer);
    expect(meta.width).toBe(320);
    expect(meta.height).toBe(240);
    expect(meta.compression).toBe("lossy");
    expect(meta.isAnimated).toBe(false);
    expect(meta.frameCount).toBe(1);
  });

  test("reads dimensions and alpha from a lossless VP8L frame", () => {
    // signature 0x2f, then packed: width-1 (14b), height-1 (14b), alpha (1b)
    const bits = (100 - 1) | ((50 - 1) << 14) | (1 << 28);
    const buffer = riff(chunk("VP8L", [0x2f, ...u32le(bits)]));
    const meta = parseWebP(buffer);
    expect(meta.width).toBe(100);
    expect(meta.height).toBe(50);
    expect(meta.hasAlpha).toBe(true);
    expect(meta.compression).toBe("lossless");
  });
});

describe("parseWebP — animation", () => {
  test("reads canvas size, frame durations and derived fps", () => {
    const buffer = riff([
      ...vp8x(0x02 | 0x10, 640, 480),        // animation + alpha flags
      ...chunk("ANIM", [0, 0, 0, 0, 3, 0]),  // bgcolor + loop count 3
      ...anmf(100),
      ...anmf(100),
      ...anmf(100),
      ...anmf(100),
    ]);

    const meta = parseWebP(buffer);
    expect(meta.width).toBe(640);
    expect(meta.height).toBe(480);
    expect(meta.isAnimated).toBe(true);
    expect(meta.hasAlpha).toBe(true);
    expect(meta.frameCount).toBe(4);
    expect(meta.loopCount).toBe(3);
    expect(meta.totalDurationMs).toBe(400);
    expect(meta.fps).toBe(10);
    expect(meta.frameDurations).toEqual([100, 100, 100, 100]);
    expect(meta.format).toContain("Animated WebP");
  });

  test("reports mixed compression when frames use both codecs", () => {
    const buffer = riff([
      ...vp8x(0x02, 64, 64),
      ...chunk("ANIM", [0, 0, 0, 0, 0, 0]),
      ...anmf(50, "VP8 "),
      ...anmf(50, "VP8L"),
    ]);
    expect(parseWebP(buffer).compression).toBe("mixed");
  });
});

describe("parseWebP — malformed input", () => {
  test("does not throw when a chunk declares more bytes than the file holds", () => {
    // A truncated EXIF chunk claiming 10 MB used to blow up with a RangeError
    // from `new Uint8Array(buffer, dataStart, chunkSize)`.
    const buffer = riff([
      ...vp8x(0x08, 32, 32),
      ...chunk("EXIF", [0x49, 0x49, 0x2a, 0x00], 10_000_000),
    ]);

    const meta = parseWebP(buffer);
    expect(meta.width).toBe(32);
    expect(meta.exifBytes).not.toBeNull();
    // Clamped to what actually exists rather than the declared length
    expect(meta.exifBytes!.length).toBeLessThanOrEqual(4);
  });

  test("does not throw on a truncated VP8X header", () => {
    const buffer = riff(chunk("VP8X", [0x02, 0, 0], 10));
    expect(() => parseWebP(buffer)).not.toThrow();
  });

  test("terminates on a zero-length chunk instead of spinning", () => {
    const buffer = riff([
      ...chunk("XMP ", [], 0),
      ...chunk("VP8L", [0x2f, ...u32le((8 - 1) | ((8 - 1) << 14))]),
    ]);
    const meta = parseWebP(buffer);
    expect(meta.width).toBe(8);
    expect(meta.height).toBe(8);
  });

  test("decodes an embedded XMP chunk as text", () => {
    const xmp = [...'<x:xmpmeta xmlns:x="adobe:ns:meta/"/>'].map((c) =>
      c.charCodeAt(0),
    );
    const buffer = riff([...vp8x(0x04, 16, 16), ...chunk("XMP ", xmp)]);
    expect(parseWebP(buffer).xmpString).toContain("xmpmeta");
  });
});
