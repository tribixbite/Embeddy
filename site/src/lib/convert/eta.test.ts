import { describe, expect, test } from "bun:test";
import {
  ETA_MIN_ELAPSED_MS,
  ETA_MIN_FRACTION,
  FRACTION_FLOOR,
  estimateRemainingMs,
  formatEta,
} from "./eta";

describe("estimateRemainingMs", () => {
  test("returns null before enough progress has accumulated", () => {
    // Below the fraction gate — a 1% sample extrapolates wildly
    expect(estimateRemainingMs(0.01, 10_000)).toBeNull();
    expect(estimateRemainingMs(ETA_MIN_FRACTION - 0.001, 10_000)).toBeNull();
  });

  test("returns null before enough time has elapsed", () => {
    // Below the elapsed gate — early samples are timing noise
    expect(estimateRemainingMs(0.5, 100)).toBeNull();
    expect(estimateRemainingMs(0.5, ETA_MIN_ELAPSED_MS - 1)).toBeNull();
  });

  test("extrapolates linearly once both gates pass", () => {
    // Half done after 10s → 10s left
    expect(estimateRemainingMs(0.5, 10_000)).toBe(10_000);
    // Quarter done after 3s → 9s left
    expect(estimateRemainingMs(0.25, 3_000)).toBe(9_000);
    // 95% done after 19s → 1s left
    expect(estimateRemainingMs(0.95, 19_000)).toBeCloseTo(1_000, 0);
  });

  test("passes exactly at the gate boundaries", () => {
    const eta = estimateRemainingMs(ETA_MIN_FRACTION, ETA_MIN_ELAPSED_MS);
    expect(eta).not.toBeNull();
    expect(eta!).toBeCloseTo(
      (ETA_MIN_ELAPSED_MS * (1 - ETA_MIN_FRACTION)) / ETA_MIN_FRACTION,
      0,
    );
  });

  test("clamps completed or overshooting fractions to zero remaining", () => {
    expect(estimateRemainingMs(1, 10_000)).toBe(0);
    expect(estimateRemainingMs(1.2, 10_000)).toBe(0);
  });

  test("rejects garbage input instead of returning Infinity/NaN", () => {
    expect(estimateRemainingMs(Number.NaN, 10_000)).toBeNull();
    expect(estimateRemainingMs(0.5, Number.NaN)).toBeNull();
    expect(estimateRemainingMs(-0.5, 10_000)).toBeNull();
    expect(estimateRemainingMs(0.5, -100)).toBeNull();
    expect(estimateRemainingMs(0.5, Number.POSITIVE_INFINITY)).toBeNull();
  });

  test("fraction floor bounds the extrapolation multiplier", () => {
    // The floor only matters if the gate is ever lowered below it, but the
    // invariant must hold: no result exceeds elapsed * (1 - floor) / floor.
    const worst = (ETA_MIN_ELAPSED_MS * (1 - FRACTION_FLOOR)) / FRACTION_FLOOR;
    const eta = estimateRemainingMs(ETA_MIN_FRACTION, ETA_MIN_ELAPSED_MS);
    expect(eta!).toBeLessThanOrEqual(worst);
  });
});

describe("formatEta", () => {
  test("formats sub-minute durations as seconds", () => {
    expect(formatEta(12_000)).toBe("~12s left");
    expect(formatEta(59_400)).toBe("~59s left"); // rounds down to 59s
  });

  test("never shows 0s for a positive remainder", () => {
    expect(formatEta(400)).toBe("~1s left");
    expect(formatEta(1)).toBe("~1s left");
  });

  test("shows 0s only when actually done", () => {
    expect(formatEta(0)).toBe("~0s left");
    expect(formatEta(-500)).toBe("~0s left");
  });

  test("formats minute durations with zero-padded seconds", () => {
    expect(formatEta(65_000)).toBe("~1m 05s left");
    expect(formatEta(60_000)).toBe("~1m 00s left");
    expect(formatEta(59_600)).toBe("~1m 00s left"); // rounds up across the boundary
    expect(formatEta(754_000)).toBe("~12m 34s left");
  });
});
