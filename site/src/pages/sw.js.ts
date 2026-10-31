/**
 * Service worker generated at build time with baked-in build ID.
 * Every build produces a new sw.js, triggering the browser's SW update flow.
 *
 * Update policy (M4): a new worker installs in the background and WAITS. It
 * no longer calls skipWaiting()+clients.claim() on its own — doing so deleted
 * the previous build's cached chunks out from under open tabs, whose next
 * lazy import then failed. The page shows an "Update available — reload"
 * prompt (lib/pwa/register-sw.ts) that posts SKIP_WAITING when the user
 * accepts, then reloads on controllerchange.
 *
 * Request routing is the tested pure function in lib/pwa/sw-routing.ts,
 * inlined here via toString().
 */
import type { APIRoute } from "astro";
import { routeRequest } from "../lib/pwa/sw-routing";
import { SHARE_CACHE, SHARE_REDIRECT_PATH } from "../lib/pwa/share-target";

declare const __BUILD_ID__: string;

const CACHE_NAME = `embeddy-${__BUILD_ID__}`;

const SW_SOURCE = `/** Embeddy SW — build ${__BUILD_ID__} */
const CACHE = "${CACHE_NAME}";
const SHARE_CACHE = "${SHARE_CACHE}";
const SHARE_REDIRECT = "${SHARE_REDIRECT_PATH}";
const routeRequest = ${routeRequest.toString()};

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
  // No skipWaiting() here: the page decides when to switch (see header)
  e.waitUntil(precache());
});

self.addEventListener("message", (e) => {
  if (e.data && e.data.type === "SKIP_WAITING") self.skipWaiting();
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) =>
        Promise.all(
          keys
            .filter((k) => k !== CACHE && k !== SHARE_CACHE)
            .map((k) => caches.delete(k))
        )
      )
      // Safe now: activation only happens on first install or after the
      // user accepted the update prompt, never under a live old page
      .then(() => self.clients.claim())
  );
});

/**
 * Web Share Target: the OS share sheet POSTs multipart files here. Stash them
 * in a dedicated cache and redirect to the page that picks them up
 * (lib/pwa/share-target.ts), choosing the tool by the first file's type.
 */
async function handleShare(request) {
  const form = await request.formData();
  const files = form.getAll("media").filter((f) => typeof f !== "string");
  const cache = await caches.open(SHARE_CACHE);
  await Promise.all((await cache.keys()).map((k) => cache.delete(k)));
  await Promise.all(
    files.map((file, i) =>
      cache.put(
        "/share-target/file/" + i,
        new Response(file, {
          headers: {
            "Content-Type": file.type || "application/octet-stream",
            "X-File-Name": encodeURIComponent(file.name || "shared"),
          },
        })
      )
    )
  );
  const first = files[0];
  const type = first ? first.type : "";
  const tool =
    type === "image/gif" || type === "image/webp" || type.indexOf("video/") === 0
      ? "/tools/convert/"
      : type.indexOf("image/") === 0
        ? "/tools/squoosh/"
        : "/tools/upload/";
  return Response.redirect(tool + SHARE_REDIRECT, 303);
}

self.addEventListener("fetch", (e) => {
  const req = e.request;
  const route = routeRequest({
    method: req.method,
    url: req.url,
    origin: self.location.origin,
    mode: req.mode,
    destination: req.destination,
  });

  if (route === "share-target") {
    e.respondWith(handleShare(req));
    return;
  }

  if (route === "network-first") {
    e.respondWith(
      fetch(req)
        .then(async (r) => {
          if (r.ok && !r.redirected) {
            const copy = await cacheable(r.clone());
            caches.open(CACHE).then((c) => c.put(req, copy));
          }
          return r;
        })
        .catch(async () => {
          // Try the exact URL, then the trailing-slash form Astro builds
          // (the manifest start_url and old links may omit it), then home
          const url = new URL(req.url);
          const slashed = url.pathname.endsWith("/") ? null : url.pathname + "/";
          return (
            (await caches.match(req, { ignoreSearch: true })) ||
            (slashed && (await caches.match(slashed))) ||
            caches.match("/")
          );
        })
    );
    return;
  }

  if (route === "cache-first") {
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
