import { describe, expect, test } from "bun:test";
import {
  MIN_CROP,
  applyAspectToCrop,
  aspectRatioFor,
  resolveCropDrag,
} from "./crop-math";
import type { CropRect } from "../../../lib/convert/types";

// ── Helpers ─────────────────────────────────────────────────────────────────

/** Pixel aspect ratio of a normalized crop rect on a given source. */
function pixelRatio(crop: CropRect, sourceW: number, sourceH: number): number {
  return (crop.w * sourceW) / (crop.h * sourceH);
}

const SOURCE_ASPECT_16_9 = 1920 / 1080;

// ── aspectRatioFor (M53) ────────────────────────────────────────────────────

describe("aspectRatioFor", () => {
  test("returns null for free mode", () => {
    expect(aspectRatioFor("free", 16, 9)).toBeNull();
  });

  test("returns the preset ratios", () => {
    expect(aspectRatioFor("1:1", 16, 9)).toBe(1);
    expect(aspectRatioFor("16:9", 0, 0)).toBeCloseTo(16 / 9);
    expect(aspectRatioFor("4:3", 0, 0)).toBeCloseTo(4 / 3);
  });

  test("custom mode uses the given ratio", () => {
    expect(aspectRatioFor("custom", 16, 9)).toBeCloseTo(16 / 9);
  });

  test("M53: custom width of 0/empty never yields 0 or Infinity", () => {
    // Clearing the W input mid-edit gives 0 (or NaN/undefined via the number
    // input) — the previous `customW / (customH || 1)` produced ratio 0 which
    // collapsed the crop to a MIN_CROP sliver.
    for (const w of [0, Number.NaN, undefined, -3]) {
      const ratio = aspectRatioFor("custom", w as number, 9);
      expect(Number.isFinite(ratio)).toBe(true);
      expect(ratio!).toBeGreaterThan(0);
    }
    for (const h of [0, Number.NaN, undefined, -3]) {
      const ratio = aspectRatioFor("custom", 16, h as number);
      expect(Number.isFinite(ratio)).toBe(true);
      expect(ratio!).toBeGreaterThan(0);
    }
  });
});

// ── resolveCropDrag: free mode ──────────────────────────────────────────────

describe("resolveCropDrag (free mode)", () => {
  test("right handle follows the pointer horizontally only", () => {
    const crop = { x: 0.1, y: 0.1, w: 0.4, h: 0.4 };
    const next = resolveCropDrag({
      crop, handle: "r", nx: 0.8, ny: 0.9,
      aspectRatio: null, sourceAspect: SOURCE_ASPECT_16_9,
    });
    expect(next.w).toBeCloseTo(0.7);
    expect(next.x).toBeCloseTo(0.1);
    expect(next.h).toBeCloseTo(0.4);
    expect(next.y).toBeCloseTo(0.1);
  });

  test("left handle anchors the right edge", () => {
    const crop = { x: 0.3, y: 0.1, w: 0.4, h: 0.4 };
    const next = resolveCropDrag({
      crop, handle: "l", nx: 0.1, ny: 0.5,
      aspectRatio: null, sourceAspect: SOURCE_ASPECT_16_9,
    });
    expect(next.x).toBeCloseTo(0.1);
    expect(next.x + next.w).toBeCloseTo(0.7);
  });

  test("never shrinks below MIN_CROP", () => {
    const crop = { x: 0.1, y: 0.1, w: 0.4, h: 0.4 };
    const next = resolveCropDrag({
      crop, handle: "r", nx: 0.05, ny: 0.1,
      aspectRatio: null, sourceAspect: SOURCE_ASPECT_16_9,
    });
    expect(next.w).toBeGreaterThanOrEqual(MIN_CROP);
  });
});

// ── resolveCropDrag: move with grab offset (M52) ────────────────────────────

describe("resolveCropDrag (move, M52)", () => {
  test("preserves the grab offset instead of centering on the pointer", () => {
    const crop = { x: 0.2, y: 0.2, w: 0.4, h: 0.4 };
    // Grabbed near the top-left of the rect (pointer at 0.25/0.25).
    const grabOffset = { dx: 0.05, dy: 0.05 };
    const next = resolveCropDrag({
      crop, handle: "move", nx: 0.3, ny: 0.3, grabOffset,
      aspectRatio: null, sourceAspect: SOURCE_ASPECT_16_9,
    });
    // Old behavior teleported the center under the pointer: x = 0.3 - 0.2 = 0.1.
    expect(next.x).toBeCloseTo(0.25);
    expect(next.y).toBeCloseTo(0.25);
    expect(next.w).toBeCloseTo(0.4);
    expect(next.h).toBeCloseTo(0.4);
  });

  test("clamps to the container bounds", () => {
    const crop = { x: 0.2, y: 0.2, w: 0.4, h: 0.4 };
    const next = resolveCropDrag({
      crop, handle: "move", nx: 1, ny: 1, grabOffset: { dx: 0.1, dy: 0.1 },
      aspectRatio: null, sourceAspect: SOURCE_ASPECT_16_9,
    });
    expect(next.x).toBeCloseTo(0.6);
    expect(next.y).toBeCloseTo(0.6);
  });
});

// ── resolveCropDrag: aspect lock (M20, M51) ─────────────────────────────────

describe("resolveCropDrag (aspect lock)", () => {
  test("M20: ratio holds when the derived height hits the bottom bound", () => {
    // 1:1 on a 16:9 source: dragging the right handle far out demands a
    // height that doesn't fit below y — the old code clamped h and left w,
    // silently producing a non-square crop while the 1:1 chip stayed lit.
    const crop = { x: 0.1, y: 0.1, w: 0.3, h: 0.3 * SOURCE_ASPECT_16_9 };
    const next = resolveCropDrag({
      crop, handle: "r", nx: 0.9, ny: 0.5,
      aspectRatio: 1, sourceAspect: SOURCE_ASPECT_16_9,
    });
    expect(pixelRatio(next, 1920, 1080)).toBeCloseTo(1);
    expect(next.y + next.h).toBeLessThanOrEqual(1 + 1e-9);
    expect(next.x + next.w).toBeLessThanOrEqual(1 + 1e-9);
    // It did actually grow toward the pointer.
    expect(next.w).toBeGreaterThan(crop.w);
  });

  test("M51: top handle resizes both axes under aspect lock", () => {
    // Old code recomputed h from the unchanged w — t/b handles sprang back.
    const crop = { x: 0.3, y: 0.3, w: 0.3, h: 0.3 * SOURCE_ASPECT_16_9 };
    const next = resolveCropDrag({
      crop, handle: "t", nx: 0.45, ny: 0.1,
      aspectRatio: 1, sourceAspect: SOURCE_ASPECT_16_9,
    });
    expect(next.h).toBeGreaterThan(crop.h);
    expect(next.w).toBeGreaterThan(crop.w);
    expect(pixelRatio(next, 1920, 1080)).toBeCloseTo(1);
    // Bottom edge stays anchored.
    expect(next.y + next.h).toBeCloseTo(crop.y + crop.h);
  });

  test("M51: bottom handle grows the crop downward keeping ratio", () => {
    const crop = { x: 0.3, y: 0.1, w: 0.3, h: 0.3 * SOURCE_ASPECT_16_9 };
    const next = resolveCropDrag({
      crop, handle: "b", nx: 0.45, ny: 0.95,
      aspectRatio: 1, sourceAspect: SOURCE_ASPECT_16_9,
    });
    expect(next.h).toBeGreaterThan(crop.h);
    expect(pixelRatio(next, 1920, 1080)).toBeCloseTo(1);
    // Top edge stays anchored.
    expect(next.y).toBeCloseTo(crop.y);
  });

  test("corner handle with top anchor keeps the bottom-right ratio-true", () => {
    const crop = { x: 0.2, y: 0.2, w: 0.4, h: 0.4 * SOURCE_ASPECT_16_9 * (9 / 16) };
    const next = resolveCropDrag({
      crop, handle: "tl", nx: 0.05, ny: 0.05,
      aspectRatio: 16 / 9, sourceAspect: SOURCE_ASPECT_16_9,
    });
    expect(pixelRatio(next, 1920, 1080)).toBeCloseTo(16 / 9);
    // Bottom-right corner anchored.
    expect(next.x + next.w).toBeCloseTo(crop.x + crop.w);
    expect(next.y + next.h).toBeCloseTo(crop.y + crop.h);
  });

  test("result always stays within [0,1] on both axes", () => {
    const crop = { x: 0.05, y: 0.05, w: 0.2, h: 0.2 };
    for (const handle of ["tl", "t", "tr", "r", "br", "b", "bl", "l"] as const) {
      for (const [nx, ny] of [[0, 0], [1, 1], [0, 1], [1, 0], [0.5, 0.5]] as const) {
        const next = resolveCropDrag({
          crop, handle, nx, ny,
          aspectRatio: 4 / 3, sourceAspect: SOURCE_ASPECT_16_9,
        });
        expect(next.x).toBeGreaterThanOrEqual(-1e-9);
        expect(next.y).toBeGreaterThanOrEqual(-1e-9);
        expect(next.x + next.w).toBeLessThanOrEqual(1 + 1e-9);
        expect(next.y + next.h).toBeLessThanOrEqual(1 + 1e-9);
        expect(next.w).toBeGreaterThan(0);
        expect(next.h).toBeGreaterThan(0);
      }
    }
  });
});

// ── applyAspectToCrop ───────────────────────────────────────────────────────

describe("applyAspectToCrop", () => {
  test("keeps the requested ratio exactly even when constrained by bounds", () => {
    // Crop pinned near the bottom edge; switching to 1:1 must reposition
    // rather than clamp one axis out of ratio.
    const crop = { x: 0.1, y: 0.7, w: 0.5, h: 0.25 };
    const next = applyAspectToCrop(crop, 1, SOURCE_ASPECT_16_9);
    expect(pixelRatio(next, 1920, 1080)).toBeCloseTo(1);
    expect(next.y + next.h).toBeLessThanOrEqual(1 + 1e-9);
    expect(next.x + next.w).toBeLessThanOrEqual(1 + 1e-9);
  });

  test("centers on the previous crop when room allows", () => {
    const crop = { x: 0.3, y: 0.3, w: 0.4, h: 0.4 };
    const next = applyAspectToCrop(crop, 16 / 9, SOURCE_ASPECT_16_9);
    expect(next.x + next.w / 2).toBeCloseTo(0.5);
    expect(next.y + next.h / 2).toBeCloseTo(0.5);
    expect(pixelRatio(next, 1920, 1080)).toBeCloseTo(16 / 9);
  });

  test("very wide ratios shrink to fit instead of overflowing", () => {
    const crop = { x: 0, y: 0, w: 1, h: 1 };
    const next = applyAspectToCrop(crop, 8, SOURCE_ASPECT_16_9);
    expect(next.w).toBeLessThanOrEqual(1 + 1e-9);
    expect(next.h).toBeGreaterThan(0);
    expect(pixelRatio(next, 1920, 1080)).toBeCloseTo(8);
  });
});
