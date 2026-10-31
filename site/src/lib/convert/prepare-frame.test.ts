import { describe, expect, test } from "bun:test";
import { IDENTITY_TRANSFORM, isIdentityTransform, planFrame, type FrameTransform } from "./prepare-frame";
import { cropRectToPixels } from "./encoder";

/** Transform with overrides on top of the identity */
function t(overrides: Partial<FrameTransform> = {}): FrameTransform {
  return { ...IDENTITY_TRANSFORM, ...overrides };
}

describe("planFrame — identity", () => {
  test("no crop/resize/rotate/flip is an identity plan over the full source", () => {
    const plan = planFrame(640, 480, t());
    expect(plan).toEqual({
      sx: 0, sy: 0, sw: 640, sh: 480,
      drawW: 640, drawH: 480, outW: 640, outH: 480,
      identity: true,
    });
  });

  test("maxDimension at or above the source size stays identity", () => {
    expect(planFrame(640, 480, t({ maxDimension: 640 })).identity).toBe(true);
    expect(planFrame(640, 480, t({ maxDimension: 4096 })).identity).toBe(true);
  });

  test("isIdentityTransform mirrors the plan flag for transforms that can't no-op", () => {
    expect(isIdentityTransform(t())).toBe(true);
    expect(isIdentityTransform(t({ rotate: 90 }))).toBe(false);
    expect(isIdentityTransform(t({ flipH: true }))).toBe(false);
    expect(isIdentityTransform(t({ crop: { x: 0, y: 0, w: 0.5, h: 0.5 } }))).toBe(false);
  });
});

describe("planFrame — crop uses the same clamped pixels as cropRectToPixels (M4)", () => {
  test("source rect and output dims match cropRectToPixels exactly", () => {
    const crop = { x: 0.333, y: 0.1, w: 0.7, h: 0.95 };
    const px = cropRectToPixels(101, 77, crop);
    const plan = planFrame(101, 77, t({ crop }));
    expect([plan.sx, plan.sy, plan.sw, plan.sh]).toEqual([px.px, px.py, px.pw, px.ph]);
    expect([plan.outW, plan.outH]).toEqual([px.pw, px.ph]);
    expect(plan.identity).toBe(false);
  });

  test("a full-frame crop rect is still identity", () => {
    expect(planFrame(320, 240, t({ crop: { x: 0, y: 0, w: 1, h: 1 } })).identity).toBe(true);
  });
});

describe("planFrame — resize", () => {
  test("fits the longer side to maxDimension, preserving aspect", () => {
    const plan = planFrame(1920, 1080, t({ maxDimension: 480 }));
    expect([plan.outW, plan.outH]).toEqual([480, 270]);
    expect([plan.drawW, plan.drawH]).toEqual([480, 270]);
  });

  test("resize applies to the cropped region, not the source", () => {
    // 1000x1000 cropped to its left half = 500x1000, fitted into 100
    const plan = planFrame(1000, 1000, t({ crop: { x: 0, y: 0, w: 0.5, h: 1 }, maxDimension: 100 }));
    expect([plan.outW, plan.outH]).toEqual([50, 100]);
  });

  test("never produces a zero-sized output for extreme aspect ratios", () => {
    const plan = planFrame(4000, 2, t({ maxDimension: 100 }));
    expect(plan.outW).toBe(100);
    expect(plan.outH).toBe(1);
  });
});

describe("planFrame — rotate / flip", () => {
  test("90 and 270 swap the output dimensions; draw dims stay pre-rotation", () => {
    for (const rotate of [90, 270] as const) {
      const plan = planFrame(640, 480, t({ rotate }));
      expect([plan.outW, plan.outH]).toEqual([480, 640]);
      expect([plan.drawW, plan.drawH]).toEqual([640, 480]);
    }
  });

  test("180 and flips keep dimensions but are not identity", () => {
    for (const extra of [{ rotate: 180 as const }, { flipH: true }, { flipV: true }]) {
      const plan = planFrame(640, 480, t(extra));
      expect([plan.outW, plan.outH]).toEqual([640, 480]);
      expect(plan.identity).toBe(false);
    }
  });

  test("rotation composes with crop + resize", () => {
    const plan = planFrame(1920, 1080, t({
      crop: { x: 0.25, y: 0, w: 0.5, h: 1 }, // 960x1080
      maxDimension: 540,                    // → 480x540
      rotate: 90,                           // → 540x480
    }));
    expect([plan.drawW, plan.drawH]).toEqual([480, 540]);
    expect([plan.outW, plan.outH]).toEqual([540, 480]);
  });
});
