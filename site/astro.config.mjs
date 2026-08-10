// @ts-check
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { defineConfig } from "astro/config";
import svelte from "@astrojs/svelte";
import sitemap from "@astrojs/sitemap";
import tailwindcss from "@tailwindcss/vite";

/**
 * Read the app version from the repo-root version.properties so the site's
 * structured data can't drift from the APK it links to.
 */
function readAppVersion() {
  try {
    const path = fileURLToPath(new URL("../version.properties", import.meta.url));
    const props = Object.fromEntries(
      readFileSync(path, "utf8")
        .split("\n")
        .filter((line) => line.includes("="))
        .map((line) => line.split("=").map((part) => part.trim())),
    );
    return `${props.VERSION_MAJOR}.${props.VERSION_MINOR}.${props.VERSION_PATCH}`;
  } catch {
    return "0.0.0";
  }
}

// Auto-incrementing build ID — changes on every build
const now = new Date();
const BUILD_ID = [
  now.getFullYear() % 100,
  String(now.getMonth() + 1).padStart(2, "0"),
  String(now.getDate()).padStart(2, "0"),
  ".",
  String(now.getHours()).padStart(2, "0"),
  String(now.getMinutes()).padStart(2, "0"),
].join("");

// https://astro.build/config
export default defineConfig({
  site: "https://embeddy.link",
  integrations: [svelte(), sitemap()],
  vite: {
    plugins: [tailwindcss()],
    define: {
      __BUILD_ID__: JSON.stringify(BUILD_ID),
      __APP_VERSION__: JSON.stringify(readAppVersion()),
    },
    worker: {
      format: "es",
    },
    optimizeDeps: {
      exclude: [
        "@jsquash/avif",
        "@jsquash/jpeg",
        "@jsquash/png",
        "@jsquash/webp",
      ],
    },
  },
});
