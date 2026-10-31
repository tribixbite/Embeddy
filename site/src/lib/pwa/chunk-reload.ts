/**
 * Recovery for stale-deploy chunk failures (M4).
 *
 * A tab opened before a deploy still references the previous build's hashed
 * chunks; GitHub Pages has already replaced them, so the next lazy import
 * (gifenc, @jsquash codecs, the encoder Worker) 404s. Reloading once fetches
 * the new HTML and its matching chunks. The reload is one-shot per build so a
 * genuinely missing chunk can't cause a reload loop.
 */

/** Messages browsers use for a failed dynamic import / module script */
const CHUNK_ERROR_PATTERNS = [
  /Failed to fetch dynamically imported module/i, // Chromium
  /error loading dynamically imported module/i, // Firefox
  /Importing a module script failed/i, // Safari
  /Unable to preload CSS/i, // Vite preload helper
  /Encoder worker failed to load/i, // our own Worker load error
];

/** True when an error looks like a failed lazy chunk/module/Worker load. */
export function isChunkLoadError(e: unknown): boolean {
  const message =
    e instanceof Error ? e.message : typeof e === "string" ? e : (e as { message?: unknown })?.message;
  if (typeof message !== "string") return false;
  return CHUNK_ERROR_PATTERNS.some((re) => re.test(message));
}

/** Minimal storage + reload surface, injectable for tests */
export interface ReloadEnv {
  storage: Pick<Storage, "getItem" | "setItem">;
  reload: () => void;
}

/**
 * If `e` is a chunk-load failure and this build hasn't already reloaded for
 * one, reload the page and return true. Returns false otherwise, so callers
 * fall through to their normal error display.
 */
export function recoverFromChunkError(e: unknown, buildId: string, env?: ReloadEnv): boolean {
  if (!isChunkLoadError(e)) return false;
  const runtime: ReloadEnv | undefined =
    env ??
    (typeof window !== "undefined"
      ? { storage: window.sessionStorage, reload: () => window.location.reload() }
      : undefined);
  if (!runtime) return false;
  const key = `embeddy:chunk-reload:${buildId}`;
  try {
    if (runtime.storage.getItem(key)) return false;
    runtime.storage.setItem(key, "1");
  } catch {
    // Storage blocked (private mode) — a reload loop is worse than an error
    return false;
  }
  runtime.reload();
  return true;
}
