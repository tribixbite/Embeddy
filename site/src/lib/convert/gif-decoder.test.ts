import { describe, expect, test } from "bun:test";
import { GIFEncoder, quantize, applyPalette } from "gifenc";
import { parseGIF } from "gifuct-js";
import { normalizeGifDelay, readGifLoopCount } from "./gif-decoder";

// ── Fixture helpers ─────────────────────────────────────────────────────────

/**
 * Encode a real 2x2 GIF with gifenc and hand back a tightly-sized buffer.
 * gifenc's `repeat` semantics: -1 = play once (no Netscape ext),
 * 0 = loop forever, N = loop count written into the Netscape extension.
 */
function encodeTestGif(repeat: number): ArrayBuffer {
  const rgba = new Uint8Array([
    255, 0, 0, 255, /**/ 0, 255, 0, 255,
    0, 0, 255, 255, /**/ 255, 255, 255, 255,
  ]);
  const gif = GIFEncoder();
  const palette = quantize(rgba, 16, { format: "rgb444" });
  const index = applyPalette(rgba, palette, "rgb444");
  gif.writeFrame(index, 2, 2, { palette, delay: 100, repeat });
  gif.finish();
  // slice() copies the written view into an exact-size buffer for parseGIF
  return gif.bytesView().slice().buffer as ArrayBuffer;
}

// ── Tests ───────────────────────────────────────────────────────────────────

describe("normalizeGifDelay (M3)", () => {
  test("passes a gifuct-js delay through unchanged — it is already milliseconds", () => {
    // gifuct-js decompressFrames() converts centiseconds → ms itself
    // (lib/index.js: `(frame.gce.delay || 10) * 10`); multiplying again
    // makes every GIF play 10x too slow.
    expect(normalizeGifDelay(100)).toBe(100);
  });

  test("defaults a missing delay (frame without a GCE) to 100 ms, not NaN", () => {
    expect(normalizeGifDelay(undefined)).toBe(100);
  });

  test("enforces the 20 ms browser minimum", () => {
    expect(normalizeGifDelay(10)).toBe(20);
  });
});

describe("readGifLoopCount (M46)", () => {
  test("reads a finite Netscape loop count", () => {
    const parsed = parseGIF(encodeTestGif(3));
    expect(readGifLoopCount(parsed)).toBe(3);
  });

  test("maps Netscape loop count 0 to 0 (loop forever)", () => {
    const parsed = parseGIF(encodeTestGif(0));
    expect(readGifLoopCount(parsed)).toBe(0);
  });

  test("maps a GIF without a Netscape extension to 1 (play once)", () => {
    const parsed = parseGIF(encodeTestGif(-1));
    expect(readGifLoopCount(parsed)).toBe(1);
  });
});
