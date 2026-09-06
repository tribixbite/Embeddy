import { describe, expect, test } from "bun:test";
import { parseGIF, decompressFrames } from "gifuct-js";
import { encodeAnimatedGif } from "./gif-encoder";
import type { ConvertOptions, DecodedFrame } from "./types";

// ── Fixture helpers ─────────────────────────────────────────────────────────

/** Full ConvertOptions with GIF-relevant defaults; crop/resize off so the
 *  encode pipeline stays canvas-free and runs under bun. */
function makeOptions(overrides: Partial<ConvertOptions> = {}): ConvertOptions {
  return {
    quality: 75,
    lossless: false,
    maxDimension: 0,
    loops: 0,
    targetFps: 10,
    outputFormat: "gif",
    crop: null,
    targetSizeBytes: 0,
    method: 4,
    minimizeSize: true,
    exact: false,
    mixed: true,
    kmin: 0,
    kmax: 0,
    snsStrength: 50,
    filterStrength: 60,
    filterSharpness: 0,
    filterType: 1,
    autofilter: 0,
    alphaQuality: 100,
    alphaCompression: 1,
    alphaFiltering: 1,
    passes: 1,
    preprocessing: 0,
    nearLossless: 100,
    sharpYuv: 0,
    targetSize: 0,
    segments: 4,
    partitionLimit: 0,
    lowMemory: 0,
    emulateJpegSize: 0,
    ...overrides,
  };
}

/** One 2x2 frame: red, green, blue, and one fully transparent pixel. */
function makeTransparentFrame(): DecodedFrame {
  return {
    rgba: new Uint8Array([
      255, 0, 0, 255, /**/ 0, 255, 0, 255,
      0, 0, 255, 255, /**/ 0, 0, 0, 0,
    ]),
    delay: 100,
  };
}

/** One 2x2 fully opaque frame. */
function makeOpaqueFrame(): DecodedFrame {
  return {
    rgba: new Uint8Array([
      255, 0, 0, 255, /**/ 0, 255, 0, 255,
      0, 0, 255, 255, /**/ 255, 255, 255, 255,
    ]),
    delay: 100,
  };
}

async function blobBytes(blob: Blob): Promise<Uint8Array> {
  return new Uint8Array(await blob.arrayBuffer());
}

// ── Tests ───────────────────────────────────────────────────────────────────

describe("encodeAnimatedGif — output buffer sizing (M14)", () => {
  test("blob ends at the GIF trailer, not at gifenc's capacity buffer", async () => {
    const blob = await encodeAnimatedGif(
      [makeOpaqueFrame(), makeOpaqueFrame()], 2, 2, makeOptions(), 10,
    );
    const bytes = await blobBytes(blob);
    // gifenc's bytesView() is a subarray of a capacity-doubling ArrayBuffer;
    // wrapping `.buffer` shipped up to ~2x trailing zero bytes after 0x3B.
    expect(bytes[bytes.length - 1]).toBe(0x3b);
  });

  test("blob is a valid GIF that round-trips through gifuct-js", async () => {
    const blob = await encodeAnimatedGif([makeOpaqueFrame()], 2, 2, makeOptions(), 10);
    const bytes = await blobBytes(blob);
    const parsed = parseGIF(bytes.slice().buffer as ArrayBuffer);
    const frames = decompressFrames(parsed, true);
    expect(frames).toHaveLength(1);
    expect(blob.type).toBe("image/gif");
  });
});

describe("encodeAnimatedGif — transparency (M32)", () => {
  test("a fully transparent pixel stays transparent in the encoded GIF", async () => {
    const blob = await encodeAnimatedGif([makeTransparentFrame()], 2, 2, makeOptions(), 10);
    const bytes = await blobBytes(blob);
    const frames = decompressFrames(parseGIF(bytes.slice().buffer as ArrayBuffer), true);
    const patch = frames[0]!.patch;
    // Pixel 3 (bottom-right) was (0,0,0,0); the old rgb444 quantize path
    // dropped alpha entirely, so it decoded as opaque black (alpha 255).
    expect(patch[15]).toBe(0);
    // Opaque pixels must remain opaque
    expect(patch[3]).toBe(255);
    expect(patch[7]).toBe(255);
    expect(patch[11]).toBe(255);
  });

  test("opaque frames keep the palette-quality rgb444 path (no alpha regression)", async () => {
    const blob = await encodeAnimatedGif([makeOpaqueFrame()], 2, 2, makeOptions(), 10);
    const bytes = await blobBytes(blob);
    const frames = decompressFrames(parseGIF(bytes.slice().buffer as ArrayBuffer), true);
    const patch = frames[0]!.patch;
    for (let i = 3; i < patch.length; i += 4) expect(patch[i]).toBe(255);
  });
});
