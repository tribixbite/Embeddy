import { describe, expect, test } from "bun:test";
import { evenFloor, qualityLevel, speedAudioProcessor, toMediabunnyGeometry } from "./video-export";
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

/** Minimal stand-in for Mediabunny's AudioSample (planar f32 only) */
class FakeAudioSample {
  readonly data: Float32Array;
  readonly format: string;
  readonly numberOfChannels: number;
  readonly sampleRate: number;
  readonly timestamp: number;
  readonly numberOfFrames: number;
  constructor(init: { data: Float32Array; format: string; numberOfChannels: number; sampleRate: number; timestamp: number }) {
    this.data = init.data;
    this.format = init.format;
    this.numberOfChannels = init.numberOfChannels;
    this.sampleRate = init.sampleRate;
    this.timestamp = init.timestamp;
    this.numberOfFrames = init.data.length / init.numberOfChannels;
  }
  copyTo(dest: Float32Array, opts: { planeIndex: number; format: string }): void {
    expect(opts.format).toBe("f32-planar");
    const n = this.numberOfFrames;
    dest.set(this.data.subarray(opts.planeIndex * n, (opts.planeIndex + 1) * n));
  }
}

describe("speedAudioProcessor", () => {
  const SR = 48_000;
  const fakeMb = { AudioSample: FakeAudioSample } as unknown as typeof import("mediabunny");

  /** Feed `seconds` of stereo audio in 1024-frame samples; collect the output */
  function run(speed: number, seconds: number) {
    const process = speedAudioProcessor(fakeMb, speed, seconds);
    const total = Math.round(seconds * SR);
    const outs: FakeAudioSample[] = [];
    for (let at = 0; at < total; at += 1024) {
      const n = Math.min(1024, total - at);
      const data = new Float32Array(n * 2);
      for (let i = 0; i < n; i++) {
        data[i] = Math.sin(((at + i) * 2 * Math.PI * 440) / SR) * 0.5; // left
        data[n + i] = 0; // right: silence
      }
      const sample = new FakeAudioSample({ data, format: "f32-planar", numberOfChannels: 2, sampleRate: SR, timestamp: at / SR });
      const out = process(sample as never) as unknown as FakeAudioSample | null;
      if (out) outs.push(out);
    }
    return { outs, total };
  }

  test.each([0.5, 2])("emits round(input / speed) frames with contiguous timestamps at %p×", (speed) => {
    const { outs, total } = run(speed, 1);
    const frames = outs.reduce((n, o) => n + o.numberOfFrames, 0);
    expect(frames).toBe(Math.round(total / speed));
    let expectedTs = 0;
    for (const o of outs) {
      expect(o.timestamp).toBeCloseTo(expectedTs, 9);
      expect(o.sampleRate).toBe(SR);
      expectedTs += o.numberOfFrames / SR;
    }
  });

  test("keeps channels planar and separate", () => {
    const { outs } = run(2, 0.5);
    for (const o of outs) {
      const n = o.numberOfFrames;
      expect(o.data.subarray(n).every((v) => v === 0)).toBe(true);
    }
    expect(outs.some((o) => o.data.subarray(0, o.numberOfFrames).some((v) => v !== 0))).toBe(true);
  });

  test("ignores samples after the window has been flushed", () => {
    const process = speedAudioProcessor(fakeMb, 2, 0.1);
    const data = new Float32Array(SR * 0.1 * 2);
    const first = new FakeAudioSample({ data, format: "f32-planar", numberOfChannels: 2, sampleRate: SR, timestamp: 0 });
    expect(process(first as never)).not.toBeNull();
    const late = new FakeAudioSample({ data, format: "f32-planar", numberOfChannels: 2, sampleRate: SR, timestamp: 0.1 });
    expect(process(late as never)).toBeNull();
  });
});
