import { describe, expect, test } from "bun:test";
import { routeRequest, type RouteInput } from "./sw-routing";

const ORIGIN = "https://embeddy.link";
const req = (overrides: Partial<RouteInput>): RouteInput => ({
  method: "GET",
  url: `${ORIGIN}/`,
  origin: ORIGIN,
  mode: "no-cors",
  destination: "",
  ...overrides,
});

describe("routeRequest", () => {
  test("cross-origin requests always pass through", () => {
    expect(routeRequest(req({ url: "https://api.embeddy.link/api/inspect?url=x" }))).toBe("passthrough");
    expect(routeRequest(req({ url: "https://0x0.st/", method: "POST" }))).toBe("passthrough");
    expect(routeRequest(req({ url: "https://cdn.example/a.png", destination: "image" }))).toBe("passthrough");
  });

  test("non-GET same-origin passes through, except the share target", () => {
    expect(routeRequest(req({ method: "POST", url: `${ORIGIN}/tools/upload/` }))).toBe("passthrough");
    expect(routeRequest(req({ method: "POST", url: `${ORIGIN}/share-target/` }))).toBe("share-target");
    expect(routeRequest(req({ method: "POST", url: `${ORIGIN}/share-target` }))).toBe("share-target");
  });

  test("navigations are network-first", () => {
    expect(routeRequest(req({ mode: "navigate", destination: "document", url: `${ORIGIN}/tools/convert/` }))).toBe("network-first");
  });

  test("build assets are cache-first, including workers and WASM (M5)", () => {
    for (const destination of ["script", "style", "image", "font", "worker"]) {
      expect(routeRequest(req({ destination, url: `${ORIGIN}/_astro/x.js` }))).toBe("cache-first");
    }
    // WASM fetched by @jsquash has an empty destination
    expect(routeRequest(req({ url: `${ORIGIN}/_astro/avif_dec.3FkjLu3p.wasm` }))).toBe("cache-first");
    expect(routeRequest(req({ url: `${ORIGIN}/_astro/chunk.js` }))).toBe("cache-first");
  });

  test("other same-origin fetches pass through", () => {
    expect(routeRequest(req({ url: `${ORIGIN}/manifest.json`, destination: "manifest" }))).toBe("passthrough");
    expect(routeRequest(req({ url: `${ORIGIN}/sw.js` }))).toBe("passthrough");
  });

  test("survives being inlined via toString (self-contained)", () => {
    // The SW embeds the function source — it must not depend on module scope
    const inlined = new Function(`return (${routeRequest.toString()});`)() as typeof routeRequest;
    expect(inlined(req({ url: `${ORIGIN}/_astro/a.wasm` }))).toBe("cache-first");
    expect(inlined(req({ mode: "navigate" }))).toBe("network-first");
  });
});
