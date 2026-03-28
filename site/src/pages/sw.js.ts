/**
 * Service worker generated at build time with baked-in build ID.
 * Every build produces a new sw.js, triggering the browser's SW update flow.
 */
import type { APIRoute } from "astro";

declare const __BUILD_ID__: string;

const CACHE_NAME = `embeddy-${__BUILD_ID__}`;

const SW_SOURCE = `/** Embeddy SW — build ${__BUILD_ID__} */
const CACHE = "${CACHE_NAME}";
const SHELL = ["/", "/tools/convert", "/tools/squoosh", "/tools/inspect", "/tools/upload"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
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
  if (e.request.mode === "navigate") {
    e.respondWith(
      fetch(e.request)
        .then((r) => {
          const clone = r.clone();
          caches.open(CACHE).then((c) => c.put(e.request, clone));
          return r;
        })
        .catch(() => caches.match(e.request).then((r) => r || caches.match("/")))
    );
    return;
  }
  if (e.request.destination === "style" || e.request.destination === "script" ||
      e.request.destination === "image" || e.request.destination === "font") {
    e.respondWith(
      caches.match(e.request).then((r) => r || fetch(e.request).then((resp) => {
        if (resp.ok) {
          const clone = resp.clone();
          caches.open(CACHE).then((c) => c.put(e.request, clone));
        }
        return resp;
      }))
    );
    return;
  }
  e.respondWith(fetch(e.request));
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
