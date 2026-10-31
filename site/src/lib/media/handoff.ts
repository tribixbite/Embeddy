/**
 * Moving a result between tools and out of the site:
 * - "Send to Upload": stash the file where the Upload tool picks it up on
 *   load — the same cache + `?shared=1` mechanism the OS share target uses
 *   (lib/pwa/share-target.ts), so there is one import path.
 * - Web Share: hand the file to the OS share sheet where supported.
 */

import { SHARE_CACHE, SHARE_REDIRECT_PATH } from "../pwa/share-target";

/** Store files for the next page's {@link takeSharedFiles}. */
export async function stashFiles(files: File[]): Promise<void> {
  const cache = await caches.open(SHARE_CACHE);
  await Promise.all((await cache.keys()).map((k) => cache.delete(k)));
  await Promise.all(
    files.map((file, i) =>
      cache.put(
        `/share-target/file/${i}`,
        new Response(file, {
          headers: {
            "Content-Type": file.type || "application/octet-stream",
            "X-File-Name": encodeURIComponent(file.name || "shared"),
          },
        }),
      ),
    ),
  );
}

/** URL of a tool page that will import stashed files on load */
export function handoffUrl(toolPath: string): string {
  return `${toolPath.replace(/\/?$/, "/")}${SHARE_REDIRECT_PATH}`;
}

/** Stash `file` and navigate to `toolPath` (e.g. "/tools/upload/"). */
export async function sendToTool(file: File, toolPath: string): Promise<void> {
  await stashFiles([file]);
  window.location.assign(handoffUrl(toolPath));
}

/** True when the Web Share API can share these files here */
export function canShareFiles(files: File[]): boolean {
  return (
    typeof navigator !== "undefined" &&
    typeof navigator.canShare === "function" &&
    navigator.canShare({ files })
  );
}

/**
 * Open the OS share sheet. Resolves false when the user dismissed it (an
 * AbortError), rethrows anything else.
 */
export async function shareFile(file: File): Promise<boolean> {
  try {
    await navigator.share({ files: [file], title: file.name });
    return true;
  } catch (e) {
    if ((e as { name?: string })?.name === "AbortError") return false;
    throw e;
  }
}
