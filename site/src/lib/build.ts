/** Build ID injected at build time by Vite define in astro.config.mjs */
declare const __BUILD_ID__: string;
export const BUILD_ID: string = __BUILD_ID__;

/** App version read from the repo-root version.properties at build time */
declare const __APP_VERSION__: string;
export const APP_VERSION: string = __APP_VERSION__;
