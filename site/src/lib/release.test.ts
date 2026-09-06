import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { apkDownloadUrl, RELEASES_LATEST_URL } from "./release";

/**
 * M36/M61 — per-ABI download links and version copy on the AbiPicker.
 * The release workflow (.github/workflows/release.yml) publishes assets named
 * `Embeddy-v<version>-<abi>.apk` under the latest release, so a stable
 * `releases/latest/download/<asset>` deep link exists for every ABI.
 */

describe("apkDownloadUrl (M36)", () => {
  test("builds the per-ABI latest-release asset URL", () => {
    expect(apkDownloadUrl("0.1.51", "arm64-v8a")).toBe(
      "https://github.com/tribixbite/Embeddy/releases/latest/download/Embeddy-v0.1.51-arm64-v8a.apk",
    );
  });

  test("matches the workflow asset naming for every published ABI", () => {
    const abis = ["arm64-v8a", "armeabi-v7a", "x86_64", "universal"];
    for (const abi of abis) {
      const url = apkDownloadUrl("1.2.3", abi);
      expect(url.endsWith(`/Embeddy-v1.2.3-${abi}.apk`)).toBe(true);
      expect(url.startsWith(`${RELEASES_LATEST_URL}/download/`)).toBe(true);
    }
  });
});

describe("AbiPicker copy (M61) and link wiring (M36)", () => {
  const pickerSource = readFileSync(
    join(import.meta.dir, "..", "components", "AbiPicker.svelte"),
    "utf8",
  );

  test("no hardcoded version string — must render APP_VERSION", () => {
    expect(pickerSource).not.toMatch(/v0\.\d+\.\d+/);
    expect(pickerSource).toContain("APP_VERSION");
  });

  test("min-Android copy matches minSdk 26 (Android 8.0)", () => {
    expect(pickerSource).toContain("Android 8.0+");
    expect(pickerSource).not.toContain("Android 7.0+");
  });

  test("download link uses the per-ABI URL builder, not the generic page", () => {
    expect(pickerSource).toContain("apkDownloadUrl");
  });
});
