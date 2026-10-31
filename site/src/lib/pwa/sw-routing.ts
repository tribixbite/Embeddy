/**
 * Fetch-routing decision for the service worker, as a pure function.
 *
 * The service worker itself is a string template (pages/sw.js.ts), which
 * can't be unit-tested. The decision of what to do with each request lives
 * here instead, is tested in sw-routing.test.ts, and is inlined into the
 * worker source with `Function.prototype.toString()` — so it must stay
 * self-contained: no imports, no references to anything outside its body.
 */

/** The subset of a Request the router looks at */
export interface RouteInput {
  method: string;
  /** Absolute request URL */
  url: string;
  /** The service worker's own origin */
  origin: string;
  /** Request.mode */
  mode: string;
  /** Request.destination */
  destination: string;
}

/**
 * - `"passthrough"` — let the browser handle it (no respondWith)
 * - `"share-target"` — POST from the OS share sheet (manifest share_target)
 * - `"network-first"` — HTML navigations: fresh when online, cache offline
 * - `"cache-first"` — immutable build assets (hashed JS/CSS, WASM, workers,
 *   fonts, images)
 */
export type Route = "passthrough" | "share-target" | "network-first" | "cache-first";

/** Path the manifest's share_target POSTs to */
export const SHARE_TARGET_PATH = "/share-target/";

/** Decide how the service worker handles a request. */
export function routeRequest(req: RouteInput): Route {
  const url = new URL(req.url);
  // Third-party requests (the API relay, upload hosts, remote OG images)
  // pass straight through untouched
  if (url.origin !== req.origin) return "passthrough";

  if (req.method === "POST" && (url.pathname === "/share-target/" || url.pathname === "/share-target")) {
    return "share-target";
  }
  // Only GETs are cacheable
  if (req.method !== "GET") return "passthrough";

  if (req.mode === "navigate") return "network-first";

  // Encoder Workers (destination "worker") and WASM fetched by the codecs
  // (destination "") were previously never cached, so Convert/Squoosh
  // failed offline even though their pages loaded (M5)
  const cacheableDestinations = ["style", "script", "image", "font", "worker", "sharedworker"];
  if (cacheableDestinations.indexOf(req.destination) !== -1) return "cache-first";
  if (url.pathname.endsWith(".wasm")) return "cache-first";
  // Vite's hashed output directory is immutable whatever fetched it
  if (url.pathname.indexOf("/_astro/") === 0) return "cache-first";

  return "passthrough";
}
