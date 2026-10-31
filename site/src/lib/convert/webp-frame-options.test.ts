import { describe, expect, test } from "bun:test";
import { getWebPFrameOptions, getWebPInitOptions } from "./webp-frame-options";
import type { ConvertOptions } from "./types";

/** Fully populated options with distinct values so a swapped mapping is caught */
function sampleOptions(): ConvertOptions {
  return {
    quality: 42,
    lossless: true,
    maxDimension: 640,
    loops: 3,
    targetFps: 12,
    outputFormat: "webp",
    crop: null,
    rotate: 0,
    flipH: false,
    flipV: false,
    trimStartMs: 0,
    trimEndMs: 0,
    speed: 1,
    muted: false,
    targetSizeBytes: 0,
    method: 5,
    minimizeSize: false,
    exact: true,
    mixed: false,
    kmin: 2,
    kmax: 9,
    snsStrength: 33,
    filterStrength: 44,
    filterSharpness: 3,
    filterType: 0,
    autofilter: 1,
    alphaQuality: 77,
    alphaCompression: 0,
    alphaFiltering: 2,
    passes: 6,
    preprocessing: 2,
    nearLossless: 60,
    sharpYuv: 1,
    targetSize: 12345,
    segments: 2,
    partitionLimit: 50,
    lowMemory: 1,
    emulateJpegSize: 1,
  };
}

describe("getWebPFrameOptions", () => {
  test("maps every camelCase field to its snake_case libwebp name", () => {
    expect(getWebPFrameOptions(sampleOptions(), 83)).toEqual({
      duration: 83,
      lossless: true,
      quality: 42,
      method: 5,
      exact: true,
      sns_strength: 33,
      filter_strength: 44,
      filter_sharpness: 3,
      filter_type: 0,
      autofilter: 1,
      alpha_quality: 77,
      alpha_compression: 0,
      alpha_filtering: 2,
      pass: 6,
      preprocessing: 2,
      near_lossless: 60,
      sharp_yuv: 1,
      target_size: 12345,
      segments: 2,
      partition_limit: 50,
      low_memory: 1,
      emulate_jpeg_size: 1,
    });
  });

  test("duration is per-frame, not taken from options", () => {
    const opts = sampleOptions();
    expect(getWebPFrameOptions(opts, 40).duration).toBe(40);
    expect(getWebPFrameOptions(opts, 100).duration).toBe(100);
  });
});

describe("getWebPInitOptions", () => {
  test("maps the animation-level fields", () => {
    expect(getWebPInitOptions(sampleOptions())).toEqual({
      minimize: false,
      loop: 3,
      mixed: false,
      kmin: 2,
      kmax: 9,
    });
  });
});
