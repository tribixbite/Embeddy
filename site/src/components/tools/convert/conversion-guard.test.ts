import { describe, expect, test } from "bun:test";
import { createGenerationGuard } from "./conversion-guard";

describe("createGenerationGuard (M17/M18)", () => {
  test("a captured token stays current until invalidated", () => {
    const guard = createGenerationGuard();
    const token = guard.token();
    expect(guard.isCurrent(token)).toBe(true);
  });

  test("invalidate() makes previously captured tokens stale", () => {
    const guard = createGenerationGuard();
    const inflight = guard.token();
    guard.invalidate(); // e.g. reset() / "Change file" mid-conversion
    expect(guard.isCurrent(inflight)).toBe(false);
  });

  test("invalidate() returns the token of the new generation", () => {
    const guard = createGenerationGuard();
    const fresh = guard.invalidate();
    expect(guard.isCurrent(fresh)).toBe(true);
    expect(fresh).toBe(guard.token());
  });

  test("each invalidation supersedes the last", () => {
    const guard = createGenerationGuard();
    const a = guard.invalidate();
    const b = guard.invalidate();
    expect(guard.isCurrent(a)).toBe(false);
    expect(guard.isCurrent(b)).toBe(true);
  });
});
