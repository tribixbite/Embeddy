import { describe, expect, test } from "bun:test";
import { buildEncodeOptions, detectMimeType } from "./compress";

// ── Fixture helpers ─────────────────────────────────────────────────────────

/** Build an ArrayBuffer from a list of bytes (padded to 12 for the sniffer). */
function bytes(...values: number[]): ArrayBuffer {
  const arr = new Uint8Array(Math.max(values.length, 12));
  arr.set(values);
  return arr.buffer;
}

/** ISO-BMFF header: 4-byte box size, "ftyp", then a 4-char major brand. */
function bmff(brand: string): ArrayBuffer {
  return bytes(
    0, 0, 0, 0x18,
    0x66, 0x74, 0x79, 0x70, // "ftyp"
    ...[...brand].map((c) => c.charCodeAt(0)),
  );
}

// ── M7: AVIF lossless mapping ───────────────────────────────────────────────

describe("buildEncodeOptions — AVIF", () => {
  test("@jsquash/avif EncodeOptions has no lossless field (regression pin)", async () => {
    const { defaultOptions } = await import("@jsquash/avif/meta");
    expect("lossless" in defaultOptions).toBe(false);
  });

  test("lossless maps to cqLevel 0 / cqAlphaLevel 0 / 4:4:4, never a lossless key", () => {
    const opts = buildEncodeOptions({ format: "avif", quality: 75, lossless: true });
    // embind silently drops unknown keys, so a `lossless` key is a no-op —
    // true lossless AVIF is cqLevel 0 + cqAlphaLevel 0 + no chroma subsampling
    expect("lossless" in opts).toBe(false);
    expect(opts.cqLevel).toBe(0);
    expect(opts.cqAlphaLevel).toBe(0);
    expect(opts.subsample).toBe(3);
  });

  test("lossy maps quality 1-100 onto cqLevel 62-0", () => {
    expect(buildEncodeOptions({ format: "avif", quality: 100, lossless: false }).cqLevel).toBe(0);
    expect(buildEncodeOptions({ format: "avif", quality: 50, lossless: false }).cqLevel).toBe(31);
    expect(buildEncodeOptions({ format: "avif", quality: 1, lossless: false }).cqLevel).toBe(61);
  });
});

describe("buildEncodeOptions — WebP / JPEG / PNG", () => {
  test("webp lossless forces quality 100 and lossless flag 1", () => {
    expect(buildEncodeOptions({ format: "webp", quality: 40, lossless: true })).toEqual({
      quality: 100,
      lossless: 1,
    });
  });

  test("webp lossy passes quality with lossless flag 0", () => {
    expect(buildEncodeOptions({ format: "webp", quality: 40, lossless: false })).toEqual({
      quality: 40,
      lossless: 0,
    });
  });

  test("jpeg only carries quality", () => {
    expect(buildEncodeOptions({ format: "jpeg", quality: 80, lossless: false })).toEqual({
      quality: 80,
    });
  });

  test("png takes no options", () => {
    expect(buildEncodeOptions({ format: "png", quality: 80, lossless: true })).toEqual({});
  });
});

// ── M57: ISO-BMFF brand check ───────────────────────────────────────────────

describe("detectMimeType", () => {
  test("recognizes JPEG / PNG / WebP magic bytes", () => {
    expect(detectMimeType(bytes(0xff, 0xd8, 0xff, 0xe0), "x")).toBe("image/jpeg");
    expect(detectMimeType(bytes(0x89, 0x50, 0x4e, 0x47), "x")).toBe("image/png");
    expect(
      detectMimeType(
        bytes(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50),
        "x",
      ),
    ).toBe("image/webp");
  });

  test("recognizes AVIF by ftyp major brand avif/avis", () => {
    expect(detectMimeType(bmff("avif"), "x")).toBe("image/avif");
    expect(detectMimeType(bmff("avis"), "x")).toBe("image/avif");
  });

  test("does NOT classify other ISO-BMFF brands (HEIC, fMP4) as AVIF", () => {
    // heic-branded ftyp box — must fall back, not hit the AVIF decoder
    expect(detectMimeType(bmff("heic"), "image/heic")).toBe("image/heic");
    expect(detectMimeType(bmff("mif1"), "image/heif")).toBe("image/heif");
    expect(detectMimeType(bmff("isom"), "video/mp4")).toBe("video/mp4");
  });

  test("falls back to the reported type for unknown bytes", () => {
    expect(detectMimeType(bytes(1, 2, 3, 4), "image/x-unknown")).toBe("image/x-unknown");
  });
});
