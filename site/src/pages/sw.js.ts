/**
 * Service worker generated at build time with baked-in build ID.
 * Every build produces a new sw.js, triggering the browser's SW update flow.
 */
import type { APIRoute } from "astro";

declare const __BUILD_ID__: string;

const CACHE_NAME = `embeddy-${__BUILD_ID__}`;

const SW_SOURCE = `/** Embeddy SW — build ${__BUILD_ID__} */
const CACHE = "${CACHE_NAME}";

// Trailing slashes match Astro's directory build output. Without them GitHub Pages
// answers with a 301, and a redirected Response can't be replayed for a navigation
// request — offline deep links would fail even though the page was "cached".
const SHELL = [
  "/",
  "/tools/convert/",
  "/tools/squoosh/",
  "/tools/inspect/",
  "/tools/upload/",
];

/**
 * Rebuild a Response from its body so nothing redirect-tainted is stored.
 * Caching a redirected Response makes navigation replays throw.
 */
async function cacheable(response) {
  const body = await response.blob();
  return new Response(body, {
    status: response.status,
    statusText: response.statusText,
    headers: response.headers,
  });
}

async function precache() {
  const cache = await caches.open(CACHE);
  // Individually, so one missing page can't abort the whole install
  await Promise.all(
    SHELL.map(async (path) => {
      try {
        const res = await fetch(path, { cache: "reload" });
        if (res.ok) await cache.put(path, await cacheable(res));
      } catch (_) {
        // Offline at install time — the runtime handler will fill this in later
      }
    })
  );
}

self.addEventListener("install", (e) => {
  e.waitUntil(precache());
  self.skipWaiting();
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener("fetch", (e) => {
  const req = e.request;

  // Only GETs are cacheable, and third-party requests (the API relay, upload
  // hosts, remote OG images) must pass straight through untouched.
  if (req.method !== "GET") return;
  if (new URL(req.url).origin !== self.location.origin) return;

  if (req.mode === "navigate") {
    e.respondWith(
      fetch(req)
        .then(async (r) => {
          if (r.ok && !r.redirected) {
            const copy = await cacheable(r.clone());
            caches.open(CACHE).then((c) => c.put(req, copy));
          }
          return r;
        })
        .catch(() =>
          caches
            .match(req, { ignoreSearch: true })
            .then((r) => r || caches.match("/"))
        )
    );
    return;
  }

  if (req.destination === "style" || req.destination === "script" ||
      req.destination === "image" || req.destination === "font") {
    e.respondWith(
      caches.match(req).then((r) => r || fetch(req).then((resp) => {
        if (resp.ok) {
          const clone = resp.clone();
          caches.open(CACHE).then((c) => c.put(req, clone));
        }
        return resp;
      }))
    );
  }
});
`;

export const GET: APIRoute = () => {
  return new Response(SW_SOURCE, {
    headers: {
      "Content-Type": "application/javascript",
      "Cache-Control": "no-cache",
    },
  });
};
