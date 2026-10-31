import { describe, expect, test } from "bun:test";
import {
  BATCH_MEMORY_LIMIT,
  MAX_BATCH_FRAMES,
  chooseVideoRoute,
  encodeToTarget,
  needsFpsRedecode,
} from "./pipeline";

const info = (width: number, height: number, seconds: number) => ({
  width,
  height,
  totalDuration: seconds * 1000,
});

describe("chooseVideoRoute", () => {
  test("small short video decodes in batch", () => {
    expect(chooseVideoRoute(info(320, 240, 5), 10)).toBe("batch");
  });

  test("streams when the decoded RGBA would exceed the memory budget", () => {
    // 1080p = ~8.3 MB/frame; 100 frames is well over 512 MB
    expect(chooseVideoRoute(info(1920, 1080, 10), 10)).toBe("streaming");
  });

  test("streams when the frame count exceeds the batch cap even if memory fits (M2)", () => {
    // 160x120 @ 10 fps for 5 min = 3000 frames ≈ 230 MB: fits memory, but
    // batch decode would silently truncate at MAX_BATCH_FRAMES
    const i = info(160, 120, 300);
    expect(3000 * 160 * 120 * 4).toBeLessThan(BATCH_MEMORY_LIMIT);
    expect(chooseVideoRoute(i, 10)).toBe("streaming");
  });

  test("exactly the batch cap still fits in batch", () => {
    expect(chooseVideoRoute(info(16, 16, MAX_BATCH_FRAMES / 10), 10)).toBe("batch");
  });

  test("custom limits are honoured", () => {
    expect(chooseVideoRoute(info(10, 10, 10), 10, { memoryLimit: 100, maxBatchFrames: 1500 })).toBe("streaming");
  });
});

describe("needsFpsRedecode", () => {
  const video = { format: "video" as const, fps: 10 };
  test("only for batch video when the slider goes above the decoded rate", () => {
    expect(needsFpsRedecode(video, 15, false)).toBe(true);
    expect(needsFpsRedecode(video, 10, false)).toBe(false);
    expect(needsFpsRedecode(video, 5, false)).toBe(false);
    expect(needsFpsRedecode(video, 15, true)).toBe(false);
    expect(needsFpsRedecode({ format: "gif" as const, fps: 10 }, 15, false)).toBe(false);
  });
});

/** Fake encoder whose output size is `bytesPerQuality * quality` */
function fakeEncoder(bytesPerQuality: number) {
  const calls: number[] = [];
  const encode = async (quality: number) => {
    calls.push(quality);
    return new Blob([new Uint8Array(bytesPerQuality * quality)]);
  };
  return { calls, encode };
}

describe("encodeToTarget", () => {
  test("no target encodes once at the requested quality", async () => {
    const { calls, encode } = fakeEncoder(10);
    const r = await encodeToTarget(encode, { startQuality: 80, targetBytes: 0 });
    expect(calls).toEqual([80]);
    expect(r).toMatchObject({ quality: 80, fits: true, attempts: 1 });
  });

  test("steps quality down until the output fits", async () => {
    const { calls, encode } = fakeEncoder(10);
    const r = await encodeToTarget(encode, { startQuality: 75, targetBytes: 500 });
    expect(calls).toEqual([75, 65, 55, 45]);
    expect(r.quality).toBe(45);
    expect(r.blob.size).toBe(450);
    expect(r.fits).toBe(true);
  });

  test("first attempt fitting stops immediately", async () => {
    const { calls, encode } = fakeEncoder(1);
    const r = await encodeToTarget(encode, { startQuality: 75, targetBytes: 1000 });
    expect(calls).toEqual([75]);
    expect(r.fits).toBe(true);
  });

  test("reports fits=false with the smallest attempt when the floor is reached (L3)", async () => {
    const { calls, encode } = fakeEncoder(1000);
    const r = await encodeToTarget(encode, { startQuality: 25, targetBytes: 10 });
    expect(calls).toEqual([25, 15, 5]);
    expect(r.fits).toBe(false);
    expect(r.quality).toBe(5);
  });

  test("always encodes at least once even below the floor", async () => {
    const { calls, encode } = fakeEncoder(1000);
    const r = await encodeToTarget(encode, { startQuality: 1, targetBytes: 10 });
    expect(calls).toEqual([1]);
    expect(r.fits).toBe(false);
  });

  test("onAttempt fires before every encode with the 1-based attempt number", async () => {
    const { encode } = fakeEncoder(10);
    const seen: Array<[number, number]> = [];
    await encodeToTarget(encode, {
      startQuality: 30,
      targetBytes: 150,
      onAttempt: (n, q) => seen.push([n, q]),
    });
    expect(seen).toEqual([[1, 30], [2, 20], [3, 10]]);
  });
});
