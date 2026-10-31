/**
 * Client-side service-worker lifecycle (M4), loaded on every page by
 * Layout.astro:
 *
 * - registers /sw.js and polls for updates (every 60 s and on tab focus);
 * - when a new worker has installed and is WAITING while this page is
 *   controlled by the old one, shows an "Update available — Reload" prompt.
 *   Accepting posts SKIP_WAITING and reloads once the new worker takes
 *   control. Declining leaves the old build (and its cached chunks) intact;
 * - recovers from stale-deploy chunk failures with a one-shot reload.
 */

import { BUILD_ID } from "../build";
import { recoverFromChunkError } from "./chunk-reload";

/** Build the update prompt. Plain DOM — no framework on every page for this. */
function showUpdatePrompt(worker: ServiceWorker) {
  if (document.getElementById("sw-update-prompt")) return;
  const bar = document.createElement("div");
  bar.id = "sw-update-prompt";
  bar.setAttribute("role", "status");
  bar.className =
    "fixed inset-x-4 bottom-[calc(1rem+env(safe-area-inset-bottom))] z-[100] mx-auto flex max-w-md items-center gap-3 " +
    "rounded-2xl border border-white/10 bg-dark-900/95 px-4 py-3 text-sm text-white/80 shadow-xl backdrop-blur";

  const label = document.createElement("span");
  label.className = "flex-1";
  label.textContent = "Update available";

  const reload = document.createElement("button");
  reload.type = "button";
  reload.className =
    "min-h-11 rounded-xl bg-brand-500 px-4 text-sm font-semibold text-white hover:bg-brand-400";
  reload.textContent = "Reload";
  reload.addEventListener("click", () => {
    reload.disabled = true;
    worker.postMessage({ type: "SKIP_WAITING" });
  });

  const dismiss = document.createElement("button");
  dismiss.type = "button";
  dismiss.className = "min-h-11 min-w-11 rounded-xl px-3 text-white/50 hover:text-white/80";
  dismiss.setAttribute("aria-label", "Dismiss update notice");
  dismiss.textContent = "✕";
  dismiss.addEventListener("click", () => bar.remove());

  bar.append(label, reload, dismiss);
  document.body.append(bar);
}

/** Watch a registration for a worker reaching "installed" behind a live one */
function watchForUpdates(reg: ServiceWorkerRegistration) {
  // An update may already be waiting from a previous visit
  if (reg.waiting && navigator.serviceWorker.controller) showUpdatePrompt(reg.waiting);

  reg.addEventListener("updatefound", () => {
    const installing = reg.installing;
    if (!installing) return;
    installing.addEventListener("statechange", () => {
      // No controller = first install, nothing to update from
      if (installing.state === "installed" && navigator.serviceWorker.controller) {
        showUpdatePrompt(installing);
      }
    });
  });
}

if ("serviceWorker" in navigator) {
  // Only reload for a controller change the user asked for — the very first
  // install's clients.claim() also fires controllerchange
  const hadController = !!navigator.serviceWorker.controller;
  let reloading = false;
  navigator.serviceWorker.addEventListener("controllerchange", () => {
    if (!hadController || reloading) return;
    reloading = true;
    window.location.reload();
  });

  navigator.serviceWorker
    .register("/sw.js")
    .then((reg) => {
      watchForUpdates(reg);
      setInterval(() => reg.update().catch(() => {}), 60_000);
      document.addEventListener("visibilitychange", () => {
        if (!document.hidden) reg.update().catch(() => {});
      });
    })
    .catch(() => {});
}

// Stale-deploy chunk failures that nothing caught (Vite's preload helper
// reports through its own event; bare dynamic imports through unhandledrejection)
window.addEventListener("vite:preloadError", (e) => {
  const payload = (e as Event & { payload?: unknown }).payload;
  if (recoverFromChunkError(payload, BUILD_ID)) e.preventDefault();
});
window.addEventListener("unhandledrejection", (e) => {
  if (recoverFromChunkError(e.reason, BUILD_ID)) e.preventDefault();
});
