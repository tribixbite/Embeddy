import { describe, expect, test } from "bun:test";
import { evenFloor, qualityLevel, toMediabunnyGeometry } from "./video-export";
import type { Rotation } from "./prepare-frame";

const W = 640;
const H = 360;

/** Our pipeline on a source point: flip (source orientation) then rotate */
function ours(x: number, y: number, rotate: Rotation, flipH: boolean, flipV: boolean): [number, number] {
  const fx = flipH ? W - x : x;
  const fy = flipV ? H - y : y;
  return rot(fx, fy, W, H, rotate);
}

/** Mediabunny's pipeline on a source point: rotate then horizontal flip */
function mediabunny(x: number, y: number, rotate: Rotation, flip: boolean): [number, number] {
  const [rx, ry] = rot(x, y, W, H, rotate);
  const outW = rotate === 90 || rotate === 270 ? H : W;
  return flip ? [outW - rx, ry] : [rx, ry];
}

function rot(x: number, y: number, w: number, h: number, r: Rotation): [number, number] {
  if (r === 90) return [h - y, x];
  if (r === 180) return [w - x, h - y];
  if (r === 270) return [y, w - x];
  return [x, y];
}

const base = { crop: null, maxDimension: 0 };

describe("toMediabunnyGeometry — same picture as our pipeline", () => {
  const rotations: Rotation[] = [0, 90, 180, 270];
  for (const rotate of rotations) {
    for (const flipH of [false, true]) {
      for (const flipV of [false, true]) {
        test(`rotate ${rotate}, flipH ${flipH}, flipV ${flipV}`, () => {
          const g = toMediabunnyGeometry(W, H, { ...base, rotate, flipH, flipV });
          for (const [x, y] of [[0, 0], [100, 50], [W, H], [W, 0], [37, 300]] as const) {
            expect(mediabunny(x, y, g.rotate, g.flip)).toEqual(ours(x, y, rotate, flipH, flipV));
          }
        });
      }
    }
  }
});

describe("toMediabunnyGeometry — crop and size", () => {
  test("crop is carried into the rotated frame", () => {
    // Left half of the source, rotated 90° clockwise → top half of the output
    const g = toMediabunnyGeometry(W, H, { ...base, crop: { x: 0, y: 0, w: 0.5, h: 1 }, rotate: 90, flipH: false, flipV: false });
    expect(g.crop).toEqual({ left: 0, top: 0, width: 360, height: 320 });
    expect([g.width, g.height]).toEqual([360, 320]);
  });

  test("crop under a horizontal flip mirrors the rect", () => {
    const g = toMediabunnyGeometry(W, H, { ...base, crop: { x: 0, y: 0, w: 0.25, h: 0.5 }, rotate: 0, flipH: true, flipV: false });
    expect(g.crop).toEqual({ left: 480, top: 0, width: 160, height: 180 });
  });

  test("output size follows maxDimension and is even", () => {
    const g = toMediabunnyGeometry(1921, 1081, { ...base, maxDimension: 641, rotate: 0, flipH: false, flipV: false });
    expect(g.width % 2).toBe(0);
    expect(g.height % 2).toBe(0);
    expect(g.width).toBe(640);
    expect(g.crop).toBeUndefined();
  });

  test("evenFloor", () => {
    expect(evenFloor(361)).toBe(360);
    expect(evenFloor(1)).toBe(2);
  });

  test("qualityLevel maps the slider onto five levels", () => {
    expect([1, 30, 60, 80, 95].map(qualityLevel)).toEqual(["very-low", "low", "medium", "high", "very-high"]);
  });
});
