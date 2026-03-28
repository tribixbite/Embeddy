/** Minimal service worker — enables PWA install prompt and basic offline shell. */
const CACHE_NAME = "embeddy-v1";
const SHELL_URLS = ["/", "/tools/convert", "/tools/squoosh", "/tools/inspect", "/tools/upload"];

self.addEventListener("install", (e) => {
  e.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(SHELL_URLS))
  );
  self.skipWaiting();
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener("fetch", (e) => {
  // Network-first for navigation, cache fallback
  if (e.request.mode === "navigate") {
    e.respondWith(
      fetch(e.request).catch(() => caches.match(e.request).then((r) => r || caches.match("/")))
    );
    return;
  }
  // Cache-first for static assets
  if (e.request.destination === "style" || e.request.destination === "script" || e.request.destination === "image") {
    e.respondWith(
      caches.match(e.request).then((r) => r || fetch(e.request).then((resp) => {
        const clone = resp.clone();
        caches.open(CACHE_NAME).then((c) => c.put(e.request, clone));
        return resp;
      }))
    );
    return;
  }
  // Pass through everything else
  e.respondWith(fetch(e.request));
});
