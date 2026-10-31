import { describe, expect, test } from "bun:test";
import {
  MIN_TRIM_MS,
  applyTimeline,
  clampSpeed,
  clampTrim,
  isFullRange,
  outputDelay,
  retimeFrames,
  sampleTimes,
  sliceFramesByTime,
} from "./timeline";
import type { DecodedFrame } from "./types";

/** n frames of `delay` ms, each tagged by its index in rgba[0] */
const frames = (n: number, delay = 100): DecodedFrame[] =>
  Array.from({ length: n }, (_, i) => ({ rgba: new Uint8Array([i]), delay }));
const ids = (fs: DecodedFrame[]) => fs.map((f) => f.rgba[0]);

describe("clampTrim", () => {
  test("unset end means the whole source", () => {
    expect(clampTrim(5000, 0, 0)).toEqual({ startMs: 0, endMs: 5000 });
  });
  test("clamps into range and keeps a minimum span", () => {
    expect(clampTrim(5000, -10, 9000)).toEqual({ startMs: 0, endMs: 5000 });
    expect(clampTrim(5000, 4990, 5000)).toEqual({ startMs: 5000 - MIN_TRIM_MS, endMs: 5000 });
    expect(clampTrim(5000, 3000, 2000)).toEqual({ startMs: 2000 - MIN_TRIM_MS, endMs: 2000 });
  });
  test("very short sources and empty sources", () => {
    expect(clampTrim(50, 0, 0)).toEqual({ startMs: 0, endMs: 50 });
    expect(clampTrim(0, 10, 20)).toEqual({ startMs: 0, endMs: 0 });
  });
  test("isFullRange", () => {
    expect(isFullRange(5000, { startMs: 0, endMs: 5000 })).toBe(true);
    expect(isFullRange(5000, { startMs: 100, endMs: 5000 })).toBe(false);
  });
});

describe("sliceFramesByTime", () => {
  test("keeps frames overlapping the window and clips edge delays", () => {
    const out = sliceFramesByTime(frames(10), 150, 420);
    expect(ids(out)).toEqual([1, 2, 3, 4]);
    expect(out.map((f) => f.delay)).toEqual([50, 100, 100, 20]);
  });
  test("full window is identity on delays and shares buffers", () => {
    const src = frames(5);
    const out = sliceFramesByTime(src, 0, 500);
    expect(ids(out)).toEqual([0, 1, 2, 3, 4]);
    expect(out[0]!.rgba).toBe(src[0]!.rgba);
  });
  test("a window past the end keeps the last frame", () => {
    expect(ids(sliceFramesByTime(frames(3), 300, 400))).toEqual([2]);
  });
});

describe("speed", () => {
  test("clampSpeed bounds and sanitises", () => {
    expect(clampSpeed(2)).toBe(2);
    expect(clampSpeed(10)).toBe(4);
    expect(clampSpeed(0.1)).toBe(0.25);
    expect(clampSpeed(NaN)).toBe(1);
    expect(clampSpeed(0)).toBe(1);
  });
  test("retimeFrames divides delays; 1x is the same array", () => {
    const src = frames(3, 100);
    expect(retimeFrames(src, 1)).toBe(src);
    expect(retimeFrames(src, 2).map((f) => f.delay)).toEqual([50, 50, 50]);
    expect(retimeFrames(src, 0.5).map((f) => f.delay)).toEqual([200, 200, 200]);
  });
  test("outputDelay floors at 20 ms", () => {
    expect(outputDelay(5)).toBe(20);
    expect(outputDelay(33.4)).toBe(33);
  });
});

describe("sampleTimes", () => {
  test("one sample per output frame across the trim window", () => {
    expect(sampleTimes({ startMs: 1000, endMs: 1500 }, 10, 1, 100)).toEqual([1, 1.1, 1.2, 1.3, 1.4]);
  });
  test("speed steps through more source per output frame", () => {
    expect(sampleTimes({ startMs: 0, endMs: 1000 }, 10, 2, 100)).toEqual([0, 0.2, 0.4, 0.6, 0.8]);
  });
  test("respects maxFrames and always yields one sample", () => {
    expect(sampleTimes({ startMs: 0, endMs: 10_000 }, 10, 1, 3)).toHaveLength(3);
    expect(sampleTimes({ startMs: 0, endMs: 20 }, 10, 1, 100)).toEqual([0]);
  });
});

describe("applyTimeline", () => {
  test("trim then speed, with the effective fps for subsampling", () => {
    const r = applyTimeline(frames(10), 10, 1000, { trimStartMs: 200, trimEndMs: 600, speed: 2 });
    expect(ids(r.frames)).toEqual([2, 3, 4, 5]);
    expect(r.frames.map((f) => f.delay)).toEqual([50, 50, 50, 50]);
    expect(r.sourceFps).toBe(20);
  });
  test("no edits returns the input untouched", () => {
    const src = frames(4);
    const r = applyTimeline(src, 10, 400, { trimStartMs: 0, trimEndMs: 0, speed: 1 });
    expect(r.frames).toBe(src);
    expect(r.sourceFps).toBe(10);
  });
});
