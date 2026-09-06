import { describe, expect, test } from "bun:test";
import { clampToRange, parseNumericInput } from "./number-input";

describe("clampToRange (M50)", () => {
  test("clamps below min and above max", () => {
    expect(clampToRange(-5, 0, 100)).toBe(0);
    expect(clampToRange(150, 0, 100)).toBe(100);
  });

  test("passes in-range values through unchanged", () => {
    expect(clampToRange(42, 0, 100)).toBe(42);
    expect(clampToRange(0.5, 0, 100)).toBe(0.5);
  });

  test("missing bounds mean unbounded on that side", () => {
    expect(clampToRange(-999, undefined, 100)).toBe(-999);
    expect(clampToRange(999999, 0, undefined)).toBe(999999);
    expect(clampToRange(7)).toBe(7);
  });
});

describe("parseNumericInput (M19/M50)", () => {
  test("M50: a typed negative value clamps to min instead of reaching encoders", () => {
    // "-5" in Loop count (min 0) previously stored loops = -5, out of spec
    // for both the WebP `loop` field and gifenc `repeat`.
    expect(parseNumericInput("-5", 0, 65535)).toBe(0);
  });

  test("M50: values above max clamp to max", () => {
    expect(parseNumericInput("99999", 0, 4096)).toBe(4096);
  });

  test("decimal input parses exactly", () => {
    expect(parseNumericInput("0.5", 0, 100)).toBe(0.5);
  });

  test("empty and non-numeric input fall back to 0, then clamp", () => {
    expect(parseNumericInput("", 0, 100)).toBe(0);
    expect(parseNumericInput("abc", 0, 100)).toBe(0);
    // With a positive min, the fallback still respects the range.
    expect(parseNumericInput("", 1, 10)).toBe(1);
  });

  test("no bounds passes the parsed value through", () => {
    expect(parseNumericInput("42")).toBe(42);
    expect(parseNumericInput("-3")).toBe(-3);
  });
});
