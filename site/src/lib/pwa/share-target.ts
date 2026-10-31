/**
 * Page side of the Web Share Target (manifest `share_target`).
 *
 * The service worker receives the OS share-sheet POST, stores the files in
 * {@link SHARE_CACHE} and redirects to `<tool>/?shared=1`; the tool calls
 * {@link takeSharedFiles} on mount to pick them up exactly once.
 */

/** Cache the SW stashes shared files in (survives SW updates) */
export const SHARE_CACHE = "embeddy-share";
/** Query string the SW appends when redirecting to a tool */
export const SHARE_REDIRECT_PATH = "?shared=1";

/** True when the current page was opened by a share-sheet redirect. */
export function isShareLaunch(search: string): boolean {
  return new URLSearchParams(search).get("shared") === "1";
}

/**
 * Read and delete the files the service worker stashed. Returns [] when
 * there are none or the Cache API is unavailable.
 */
export async function takeSharedFiles(): Promise<File[]> {
  if (typeof caches === "undefined") return [];
  const cache = await caches.open(SHARE_CACHE);
  const requests = await cache.keys();
  const files: File[] = [];
  for (const request of requests) {
    const response = await cache.match(request);
    if (!response) continue;
    const blob = await response.blob();
    const name = decodeURIComponent(response.headers.get("X-File-Name") ?? "shared");
    files.push(new File([blob], name, { type: blob.type || response.headers.get("Content-Type") || "" }));
    await cache.delete(request);
  }
  return files;
}

/**
 * If this page load came from the share sheet, return the shared files and
 * strip `?shared=1` from the URL so a reload doesn't re-trigger the import.
 */
export async function consumeShareLaunch(): Promise<File[]> {
  if (typeof window === "undefined" || !isShareLaunch(window.location.search)) return [];
  const url = new URL(window.location.href);
  url.searchParams.delete("shared");
  window.history.replaceState(null, "", url.pathname + url.search + url.hash);
  return takeSharedFiles();
}
