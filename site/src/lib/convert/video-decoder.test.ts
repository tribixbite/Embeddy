import { describe, expect, test } from "bun:test";
import { availableFrameCount } from "./video-decoder";

describe("availableFrameCount (M47)", () => {
  test("a sub-frame-interval video still yields one frame", () => {
    // 0.05 s at 10 fps used to floor to 0 frames — "0 frames" in the UI
    // and a Convert button that silently no-ops.
    expect(availableFrameCount(0.05, 10)).toBe(1);
  });

  test("normal durations floor to whole frames", () => {
    expect(availableFrameCount(1, 10)).toBe(10);
    expect(availableFrameCount(2.55, 10)).toBe(25);
  });
});
