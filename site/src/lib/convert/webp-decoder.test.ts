import { describe, expect, test } from "bun:test";
import { repetitionCountToLoops } from "./webp-decoder";

describe("repetitionCountToLoops (M46)", () => {
  test("Infinity (loop forever) maps to 0", () => {
    expect(repetitionCountToLoops(Infinity)).toBe(0);
  });

  test("0 repetitions (play once) maps to 1", () => {
    expect(repetitionCountToLoops(0)).toBe(1);
  });

  test("a finite repetition count passes through", () => {
    expect(repetitionCountToLoops(5)).toBe(5);
  });
});
