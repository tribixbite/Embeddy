import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { HOST_LIMITS, RELAY_MAX_BYTES } from "./types";

/**
 * M13/M38 — the relay cap and everything derived from it.
 * The worker double-buffers uploads (~2x file size peak vs the 128 MB isolate
 * limit), so the cap was lowered to 50 MB. The site constant, and any copy
 * shown to users, must move in lockstep with the worker.
 */

const repoRoot = join(import.meta.dir, "..", "..", "..", "..");

describe("RELAY_MAX_BYTES (M13 site half)", () => {
  test("is 50 MB — the double-buffering-safe worker cap", () => {
    expect(RELAY_MAX_BYTES).toBe(50 * 1024 * 1024);
  });

  test("stays in sync with worker MAX_UPLOAD_BYTES", () => {
    const workerSource = readFileSync(
      join(repoRoot, "worker", "src", "index.ts"),
      "utf8",
    );
    const match = workerSource.match(
      /const MAX_UPLOAD_BYTES = (\d+) \* 1024 \* 1024/,
    );
    expect(match).not.toBeNull();
    const workerCap = Number(match![1]) * 1024 * 1024;
    expect(RELAY_MAX_BYTES).toBe(workerCap);
  });

  test("every host limit is clamped to the relay cap", () => {
    for (const limit of Object.values(HOST_LIMITS)) {
      expect(limit).toBeLessThanOrEqual(RELAY_MAX_BYTES);
      expect(limit).toBeGreaterThan(0);
    }
  });
});

describe("host chip copy derives from HOST_LIMITS (M38)", () => {
  const selectorSource = readFileSync(
    join(
      repoRoot,
      "site",
      "src",
      "components",
      "tools",
      "upload",
      "HostSelector.svelte",
    ),
    "utf8",
  );

  test("no hardcoded host-native size literals remain", () => {
    // The hosts' native limits (512 MB / 200 MB) are unreachable through the
    // relay; quoting them contradicts the preflight rejection.
    expect(selectorSource).not.toMatch(/512\s*MB/);
    expect(selectorSource).not.toMatch(/200\s*MB/);
  });

  test("chip copy is generated from HOST_LIMITS via formatFileSize", () => {
    expect(selectorSource).toContain("HOST_LIMITS");
    expect(selectorSource).toContain("formatFileSize");
  });
});
