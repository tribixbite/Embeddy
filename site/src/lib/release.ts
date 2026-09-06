/**
 * GitHub release download links for the Android app.
 * The release workflow (.github/workflows/release.yml) publishes one APK per
 * ABI named `Embeddy-v<version>-<abi>.apk`, so `releases/latest/download/`
 * gives a stable per-ABI deep link without knowing the release tag.
 */

/** The latest-release landing page (fallback / "all downloads" link). */
export const RELEASES_LATEST_URL =
  "https://github.com/tribixbite/Embeddy/releases/latest";

/**
 * Direct download URL for a specific ABI's APK from the latest release.
 * @param version App version without the leading `v` (e.g. "0.1.51")
 * @param abi Release ABI: arm64-v8a | armeabi-v7a | x86_64 | universal
 */
export function apkDownloadUrl(version: string, abi: string): string {
  return `${RELEASES_LATEST_URL}/download/Embeddy-v${version}-${abi}.apk`;
}
