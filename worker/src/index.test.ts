import { afterEach, describe, expect, test } from "bun:test";
import app from "./index";

/**
 * Route-level tests for the Hono app. Upstream/DoH traffic is stubbed via
 * globalThis.fetch so status-code contracts are pinned without network.
 */

const realFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = realFetch;
});

/** Install a fetch stub routed by URL. */
function installFetch(handler: (url: string) => Response | Promise<Response>): void {
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    void init;
    const url =
      typeof input === "string"
        ? input
        : input instanceof URL
          ? input.toString()
          : input.url;
    return handler(url);
  }) as typeof fetch;
}

/** DoH JSON response with a single public A record. */
function dohPublic(): Response {
  return new Response(
    JSON.stringify({ Status: 0, Answer: [{ name: "stub.", type: 1, TTL: 60, data: "93.184.216.34" }] }),
    { headers: { "content-type": "application/dns-json" } },
  );
}

describe("POST /api/upload — Content-Length required (M42)", () => {
  test("rejects a request without Content-Length with 411 before buffering", async () => {
    const res = await app.request("/api/upload", { method: "POST" });
    expect(res.status).toBe(411);
    const body = (await res.json()) as { error?: string };
    expect(body.error ?? "").toContain("Content-Length");
  });

  test("still relays a well-formed upload that declares its length", async () => {
    installFetch((url) =>
      url.startsWith("https://0x0.st")
        ? new Response("https://0x0.st/abc.bin\n")
        : new Response("unexpected", { status: 500 }),
    );

    const form = new FormData();
    form.append("file", new Blob([new Uint8Array(64)]), "x.bin");
    form.append("host", "0x0.st");

    const res = await app.request("/api/upload", {
      method: "POST",
      body: form,
      // bun's Request does not auto-compute Content-Length for FormData the
      // way browsers do at send time, so declare a plausible value explicitly.
      headers: { "content-length": "512" },
    });

    expect(res.status).toBe(200);
    const body = (await res.json()) as { url?: string };
    expect(body.url).toBe("https://0x0.st/abc.bin");
  });
});

describe("GET /api/inspect — blocked redirect maps to 403 (M44)", () => {
  test("a redirect into private address space returns 403, not 502", async () => {
    installFetch((url) => {
      if (url.includes("cloudflare-dns.com")) return dohPublic();
      return new Response(null, {
        status: 302,
        headers: { location: "http://169.254.169.254/latest/meta-data/" },
      });
    });

    const res = await app.request(
      `/api/inspect?url=${encodeURIComponent("https://example.com/")}`,
    );
    expect(res.status).toBe(403);
    const body = (await res.json()) as { error?: string };
    expect(body.error ?? "").toContain("not allowed");
  });
});

describe("GET /health — relay cap (M13)", () => {
  test("upload cap leaves headroom for the relay's double-buffering", async () => {
    const res = await app.request("/health");
    expect(res.status).toBe(200);
    const body = (await res.json()) as { limits: { maxUploadBytes: number } };
    // formData() + the rebuilt upstream FormData hold two in-memory copies, so
    // the cap must keep 2x + overhead under the 128 MB isolate limit.
    expect(body.limits.maxUploadBytes).toBe(50 * 1024 * 1024);
  });
});
