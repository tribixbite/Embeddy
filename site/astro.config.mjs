// @ts-check
import { defineConfig } from "astro/config";
import svelte from "@astrojs/svelte";
import sitemap from "@astrojs/sitemap";
import tailwindcss from "@tailwindcss/vite";

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
        "wasm-webp",
      ],
    },
  },
});
