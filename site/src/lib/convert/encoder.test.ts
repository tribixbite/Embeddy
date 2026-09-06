import { describe, expect, test } from "bun:test";
import { cropRectToPixels, subsampleFrames } from "./encoder";
import type { DecodedFrame } from "./types";

// ── Fixture helpers ─────────────────────────────────────────────────────────

/** Build N single-pixel frames with the given per-frame delays. */
function makeFrames(delays: number[]): DecodedFrame[] {
  return delays.map((delay) => ({ rgba: new Uint8Array(4), delay }));
}

// ── Tests ───────────────────────────────────────────────────────────────────

describe("cropRectToPixels (M4)", () => {
  test("clamps the crop width so px + pw never exceeds the source width", () => {
    // {x:0.5, w:0.5} on a 101 px source: px = round(50.5) = 51, so only
    // 50 columns remain. The naive `round(crop.w * srcW)` = 51 overflows
    // and produced a declared-vs-actual buffer mismatch in both batch encoders.
    const { px, pw } = cropRectToPixels(101, 100, { x: 0.5, y: 0, w: 0.5, h: 1 });
    expect(px).toBe(51);
    expect(pw).toBe(50);
    expect(px + pw).toBeLessThanOrEqual(101);
    // Document the mismatch with the formula callers previously used
    expect(Math.round(0.5 * 101)).not.toBe(pw);
  });

  test("clamps height the same way", () => {
    const { py, ph } = cropRectToPixels(100, 101, { x: 0, y: 0.5, w: 1, h: 0.5 });
    expect(py + ph).toBeLessThanOrEqual(101);
    expect(ph).toBe(50);
  });

  test("an untruncated crop keeps its rounded dimensions", () => {
    const r = cropRectToPixels(200, 100, { x: 0.25, y: 0.25, w: 0.5, h: 0.5 });
    expect(r).toEqual({ px: 50, py: 25, pw: 100, ph: 50 });
  });

  test("degenerate offsets still yield at least a 1x1 region inside bounds", () => {
    const r = cropRectToPixels(100, 100, { x: 1, y: 1, w: 0.5, h: 0.5 });
    expect(r.pw).toBeGreaterThanOrEqual(1);
    expect(r.ph).toBeGreaterThanOrEqual(1);
    expect(r.px + r.pw).toBeLessThanOrEqual(100);
    expect(r.py + r.ph).toBeLessThanOrEqual(100);
  });
});

describe("subsampleFrames (M16)", () => {
  test("does not subsample when target FPS is within 1 fps of the source", () => {
    // Default targetFps is Math.round(info.fps): a 10.4 fps GIF seeds 10,
    // which used to drop ~4% of frames and flatten all variable delays.
    const frames = makeFrames(
      Array.from({ length: 104 }, (_, i) => (i % 2 === 0 ? 80 : 120)),
    );
    const result = subsampleFrames(frames, 10.4, 10);
    expect(result).toHaveLength(104);
    expect(result.map((f) => f.delay)).toEqual(frames.map((f) => f.delay));
  });

  test("still subsamples when target FPS is genuinely lower", () => {
    const frames = makeFrames(Array.from({ length: 20 }, () => 50));
    const result = subsampleFrames(frames, 20, 10);
    expect(result).toHaveLength(10);
  });

  test("preserves total duration and variable timing when subsampling", () => {
    const delays = [50, 50, 100, 100, 50, 50, 100, 100, 50, 50]; // 700 ms total
    const frames = makeFrames(delays);
    const result = subsampleFrames(frames, 20, 10);

    const totalIn = delays.reduce((a, b) => a + b, 0);
    const totalOut = result.reduce((a, f) => a + f.delay, 0);
    expect(totalOut).toBe(totalIn);
    // Variable timing must survive — the old code overwrote every delay
    // with a constant 1000/targetFps.
    const unique = new Set(result.map((f) => f.delay));
    expect(unique.size).toBeGreaterThan(1);
  });

  test("returns frames untouched when target FPS is 0 or above source", () => {
    const frames = makeFrames([100, 100, 100]);
    expect(subsampleFrames(frames, 10, 0)).toBe(frames);
    expect(subsampleFrames(frames, 10, 30)).toBe(frames);
  });
});
