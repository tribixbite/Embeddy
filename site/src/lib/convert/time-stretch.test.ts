import { describe, expect, test } from "bun:test";
import { WsolaStretcher, type PlanarAudio } from "./time-stretch";

const SR = 48_000;

/** Mono sine of `seconds` at `hz` */
function sine(hz: number, seconds: number, amp = 0.5): Float32Array {
  const n = Math.round(seconds * SR);
  const out = new Float32Array(n);
  for (let i = 0; i < n; i++) out[i] = amp * Math.sin((2 * Math.PI * hz * i) / SR);
  return out;
}

/** Run a whole signal through the stretcher in fixed-size chunks */
function stretch(input: PlanarAudio, speed: number, chunk = 1024): PlanarAudio {
  const s = new WsolaStretcher(speed, SR, input.length);
  const parts: PlanarAudio[] = [];
  for (let at = 0; at < input[0]!.length; at += chunk) {
    parts.push(s.push(input.map((ch) => ch.subarray(at, at + chunk))));
  }
  parts.push(s.flush());
  const total = parts.reduce((n, p) => n + p[0]!.length, 0);
  return input.map((_, c) => {
    const out = new Float32Array(total);
    let at = 0;
    for (const p of parts) {
      out.set(p[c]!, at);
      at += p[c]!.length;
    }
    return out;
  });
}

/** Frequency estimate from rising zero crossings over the middle half */
function dominantHz(x: Float32Array): number {
  const a = Math.floor(x.length / 4);
  const b = Math.floor((3 * x.length) / 4);
  let first = -1;
  let last = -1;
  let count = 0;
  for (let i = a + 1; i < b; i++) {
    if (x[i - 1]! < 0 && x[i]! >= 0) {
      if (first < 0) first = i;
      last = i;
      count++;
    }
  }
  return ((count - 1) * SR) / (last - first);
}

function rmsMiddle(x: Float32Array): number {
  const a = Math.floor(x.length / 4);
  const b = Math.floor((3 * x.length) / 4);
  let sum = 0;
  for (let i = a; i < b; i++) sum += x[i]! * x[i]!;
  return Math.sqrt(sum / (b - a));
}

describe("WsolaStretcher", () => {
  test.each([0.25, 0.5, 0.75, 1, 1.5, 2, 3, 4])("output length is round(input / speed) at %p×", (speed) => {
    const input = [sine(440, 1.3)];
    const out = stretch(input, speed);
    expect(out[0]!.length).toBe(Math.round(input[0]!.length / speed));
  });

  test.each([0.5, 2, 4])("keeps pitch at %p×", (speed) => {
    const out = stretch([sine(440, 2)], speed);
    expect(dominantHz(out[0]!)).toBeCloseTo(440, -1); // within ±5 Hz
  });

  test.each([0.5, 2])("keeps loudness at %p×", (speed) => {
    const input = sine(330, 2);
    const out = stretch([input], speed);
    const ratio = rmsMiddle(out[0]!) / rmsMiddle(input);
    expect(ratio).toBeGreaterThan(0.85);
    expect(ratio).toBeLessThan(1.15);
  });

  test("result does not depend on how the input is chunked", () => {
    const input = [sine(523, 1), sine(220, 1, 0.3)];
    const a = stretch(input, 1.5, 128);
    const b = stretch(input, 1.5, 4096);
    const c = stretch(input, 1.5, 100_000);
    for (let ch = 0; ch < 2; ch++) {
      expect(Array.from(b[ch]!)).toEqual(Array.from(a[ch]!));
      expect(Array.from(c[ch]!)).toEqual(Array.from(a[ch]!));
    }
  });

  test("channels stay separate", () => {
    const left = sine(440, 1);
    const right = new Float32Array(left.length);
    const [, outR] = stretch([left, right], 2) as [Float32Array, Float32Array];
    expect(outR.every((v) => v === 0)).toBe(true);
  });

  test("returns output while streaming, not only on flush", () => {
    const s = new WsolaStretcher(2, SR, 1);
    const out = s.push([sine(440, 1)]);
    expect(out[0]!.length).toBeGreaterThan(SR * 0.4);
  });

  test("rejects unsupported speeds and channel mismatches", () => {
    expect(() => new WsolaStretcher(0.1, SR, 1)).toThrow(RangeError);
    expect(() => new WsolaStretcher(5, SR, 1)).toThrow(RangeError);
    expect(() => new WsolaStretcher(2, SR, 1).push([new Float32Array(4), new Float32Array(4)])).toThrow(RangeError);
  });

  test("push after flush throws; second flush is empty", () => {
    const s = new WsolaStretcher(2, SR, 1);
    s.push([sine(440, 0.2)]);
    s.flush();
    expect(s.flush()[0]!.length).toBe(0);
    expect(() => s.push([new Float32Array(1)])).toThrow();
  });
});
