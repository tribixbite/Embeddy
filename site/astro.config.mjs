// @ts-check
import { defineConfig } from "astro/config";
import svelte from "@astrojs/svelte";
import sitemap from "@astrojs/sitemap";
import tailwindcss from "@tailwindcss/vite";

// https://astro.build/config
export default defineConfig({
  site: "https://embeddy.link",
  integrations: [svelte(), sitemap()],
  vite: {
    plugins: [tailwindcss()],
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
