import { afterEach, describe, expect, test } from "bun:test";
import app from "./index";
import {
  MAX_ERROR_TEXT_CHARS,
  MAX_TAGS,
  MAX_TAG_CONTENT_CHARS,
  drainCapped,
  isTimeoutError,
  truncate,
} from "./limits";

/**
 * /api/inspect extraction contract and the limits that protect it.
 * Upstream + DoH are stubbed via globalThis.fetch.
 */

const realFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = realFetch;
});

type Handler = (url: string, init?: RequestInit) => Response | Promise<Response>;

/** Stub fetch: DoH lookups answer with a public address, the rest go to `handler`. */
function installFetch(handler: Handler): RequestInit[] {
  const inits: RequestInit[] = [];
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    if (url.includes("cloudflare-dns.com")) {
      return new Response(
        JSON.stringify({ Status: 0, Answer: [{ name: "stub.", type: 1, TTL: 60, data: "93.184.216.34" }] }),
        { headers: { "content-type": "application/dns-json" } },
      );
    }
    inits.push(init ?? {});
    return handler(url, init);
  }) as typeof fetch;
  return inits;
}

const html = (body: string, status = 200) =>
  new Response(body, { status, headers: { "content-type": "text/html; charset=utf-8" } });

async function inspect(url: string) {
  const res = await app.request(`/api/inspect?url=${encodeURIComponent(url)}`);
  return { status: res.status, body: (await res.json()) as Record<string, unknown> };
}

describe("GET /api/inspect — meta extraction", () => {
  test("extracts title, OG, Twitter, theme-color and favicon", async () => {
    installFetch(() =>
      html(`<!doctype html><html><head>
        <title>  Example Page </title>
        <meta property="og:title" content="OG Title">
        <meta property="og:description" content="OG Desc">
        <meta property="og:site_name" content="Example">
        <meta property="og:image" content="https://cdn.example.com/a.png">
        <meta name="twitter:card" content="summary_large_image">
        <meta name="theme-color" content="#123456">
        <link rel="shortcut icon" href="/favicon.ico">
      </head><body></body></html>`),
    );
    const { status, body } = await inspect("https://example.com/page");
    expect(status).toBe(200);
    expect(body).toMatchObject({
      url: "https://example.com/page",
      status: 200,
      title: "Example Page",
      ogTitle: "OG Title",
      ogDescription: "OG Desc",
      ogSiteName: "Example",
      ogImage: "https://cdn.example.com/a.png",
      twitterCard: "summary_large_image",
      themeColor: "#123456",
      favicon: "https://example.com/favicon.ico",
    });
  });

  test("falls back to <meta name=description> when og:description is absent", async () => {
    installFetch(() => html(`<head><meta name="description" content="Plain desc"></head>`));
    const { body } = await inspect("https://example.com/");
    expect(body.ogDescription).toBe("Plain desc");
  });

  test("relative og:image and favicon resolve against the post-redirect URL", async () => {
    installFetch((url) => {
      if (url === "https://example.com/old") {
        return new Response(null, { status: 301, headers: { location: "https://www.example.com/new/page" } });
      }
      return html(`<head><meta property="og:image" content="img/card.png"><link rel="icon" href="../fav.png"></head>`);
    });
    const { status, body } = await inspect("https://example.com/old");
    expect(status).toBe(200);
    expect(body.url).toBe("https://www.example.com/new/page");
    expect(body.ogImage).toBe("https://www.example.com/new/img/card.png");
    expect(body.favicon).toBe("https://www.example.com/fav.png");
  });

  test("non-HTML content is 422 and its body is released", async () => {
    let cancelled = false;
    installFetch(
      () =>
        new Response(
          new ReadableStream({
            pull(c) {
              c.enqueue(new Uint8Array(16));
            },
            cancel() {
              cancelled = true;
            },
          }),
          { headers: { "content-type": "image/png" } },
        ),
    );
    const { status, body } = await inspect("https://example.com/a.png");
    expect(status).toBe(422);
    expect(String(body.error)).toContain("HTML");
    expect(cancelled).toBe(true);
  });

  test("upstream error status is 502", async () => {
    installFetch(() => html("nope", 500));
    const { status, body } = await inspect("https://example.com/");
    expect(status).toBe(502);
    expect(String(body.error)).toContain("500");
  });
});

describe("GET /api/inspect — limits", () => {
  test("upstream fetch carries a deadline signal (M6)", async () => {
    const inits = installFetch(() => html("<title>x</title>"));
    await inspect("https://example.com/");
    expect(inits[0]?.signal).toBeInstanceOf(AbortSignal);
  });

  test("a timed-out upstream maps to 504 (M6)", async () => {
    installFetch(() => {
      throw new DOMException("The operation timed out.", "TimeoutError");
    });
    const { status, body } = await inspect("https://example.com/");
    expect(status).toBe(504);
    expect(String(body.error)).toContain("did not respond");
  });

  test("tag count and content length are capped (L1)", async () => {
    const long = "x".repeat(MAX_TAG_CONTENT_CHARS * 2);
    const metas = Array.from({ length: MAX_TAGS + 50 }, (_, i) => `<meta name="k${i}" content="${long}">`).join("");
    installFetch(() => html(`<head>${metas}</head>`));
    const { body } = await inspect("https://example.com/");
    const tags = body.tags as Array<{ property: string; content: string }>;
    expect(tags.length).toBe(MAX_TAGS);
    expect(tags[0]!.content.length).toBe(MAX_TAG_CONTENT_CHARS);
  });
});

describe("POST /api/upload — limits", () => {
  test("upstream error pages are truncated in the echoed message (L1)", async () => {
    installFetch(() => new Response("E".repeat(10_000), { status: 503 }));
    const form = new FormData();
    form.append("file", new Blob([new Uint8Array(8)]), "x.bin");
    const res = await app.request("/api/upload", {
      method: "POST",
      body: form,
      headers: { "content-length": "256" },
    });
    expect(res.status).toBe(502);
    const body = (await res.json()) as { error: string };
    expect(body.error.length).toBeLessThan(MAX_ERROR_TEXT_CHARS + 60);
  });

  test("a timed-out upload host maps to 504 (M6)", async () => {
    const inits = installFetch(() => {
      throw new DOMException("The operation timed out.", "TimeoutError");
    });
    const form = new FormData();
    form.append("file", new Blob([new Uint8Array(8)]), "x.bin");
    const res = await app.request("/api/upload", {
      method: "POST",
      body: form,
      headers: { "content-length": "256" },
    });
    expect(res.status).toBe(504);
    expect(inits[0]?.signal).toBeInstanceOf(AbortSignal);
  });
});

describe("drainCapped", () => {
  test("stops at the cap and cancels an endless body", async () => {
    let cancelled = false;
    const endless = new Response(
      new ReadableStream({
        pull(c) {
          c.enqueue(new Uint8Array(1024));
        },
        cancel() {
          cancelled = true;
        },
      }),
    );
    const read = await drainCapped(endless, 10 * 1024);
    expect(read).toBeGreaterThanOrEqual(10 * 1024);
    expect(read).toBeLessThan(12 * 1024);
    expect(cancelled).toBe(true);
  });

  test("reads a short body to completion", async () => {
    expect(await drainCapped(new Response(new Uint8Array(100)), 1024)).toBe(100);
    expect(await drainCapped(new Response(null), 1024)).toBe(0);
  });
});

describe("truncate / isTimeoutError", () => {
  test("truncate keeps short strings and marks cuts", () => {
    expect(truncate("abc", 5)).toBe("abc");
    expect(truncate("abcdef", 4)).toBe("abc…");
    expect(truncate("abcdef", 4).length).toBe(4);
  });
  test("isTimeoutError recognises timeout and abort DOMExceptions", () => {
    expect(isTimeoutError(new DOMException("t", "TimeoutError"))).toBe(true);
    expect(isTimeoutError(new DOMException("a", "AbortError"))).toBe(true);
    expect(isTimeoutError(new Error("x"))).toBe(false);
  });
});
