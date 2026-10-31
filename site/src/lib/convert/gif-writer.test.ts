import { describe, expect, test } from "bun:test";
import { frameHasTransparency, loopsToRepeat, qualityToMaxColors } from "./gif-writer";

describe("gif-writer helpers", () => {
  test("qualityToMaxColors maps 1-100 onto 16-256", () => {
    expect(qualityToMaxColors(1)).toBe(16);
    expect(qualityToMaxColors(50)).toBe(128);
    expect(qualityToMaxColors(100)).toBe(256);
    expect(qualityToMaxColors(500)).toBe(256);
  });

  test("loopsToRepeat: play-once drops the Netscape extension", () => {
    expect(loopsToRepeat(0)).toBe(0);
    expect(loopsToRepeat(1)).toBe(-1);
    expect(loopsToRepeat(3)).toBe(3);
  });

  test("frameHasTransparency uses the 1-bit threshold", () => {
    expect(frameHasTransparency(new Uint8Array([0, 0, 0, 255, 0, 0, 0, 128]))).toBe(false);
    expect(frameHasTransparency(new Uint8Array([0, 0, 0, 255, 0, 0, 0, 127]))).toBe(true);
  });
});
