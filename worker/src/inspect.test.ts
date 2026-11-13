import { afterEach, describe, expect, test } from "bun:test";
import app from "./index";
import { isLinkedEmbedHostAllowed } from "./discord-sources";
import { decodeHtmlEntities } from "./html-entities";
import {
  MAX_EMBED_JSON_BYTES,
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

  test("decodes HTML entities in values, titles and hrefs (HTMLRewriter returns them raw)", async () => {
    const seen: string[] = [];
    installFetch((url) => {
      seen.push(url);
      if (url.includes("oembed")) return new Response("{}");
      return html(`<head><title>Video &amp; GIF</title>
        <meta property="og:title" content="Tom &amp; Jerry&#39;s &quot;Show&quot; &mdash; &#x1F600; &unknown;">
        <link type="application/json+oembed" href="/oembed?format=json&amp;url=x"></head>`);
    });
    const { body } = await inspect("https://example.com/");
    expect(body.title).toBe("Video & GIF");
    expect((body.tags as Array<{ content: string }>)[0]!.content).toBe('Tom & Jerry\'s "Show" — 😀 &unknown;');
    expect(seen[1]).toBe("https://example.com/oembed?format=json&url=x");
  });

  test("reports the upstream HTML size it read", async () => {
    installFetch(() => html(`<head><title>x</title></head>${"a".repeat(5000)}`));
    const { body } = await inspect("https://example.com/");
    expect((body.fetch as { bytesRead: number }).bytesRead).toBe(5000 + "<head><title>x</title></head>".length);
  });

  test("only the first <title> counts — inline SVG titles in <body> are ignored", async () => {
    installFetch(() =>
      html(`<head><title>Profile · GitHub</title></head><body><svg><title>LinkedIn</title></svg></body>`),
    );
    const { body } = await inspect("https://example.com/");
    expect(body.title).toBe("Profile · GitHub");
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

  test("a direct image URL is a media result (Discord previews it bare) and its body is released", async () => {
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
    expect(status).toBe(200);
    expect(body.direct).toEqual({ kind: "image" });
    expect(body.tags).toEqual([]);
    expect(cancelled).toBe(true);
  });

  test("non-HTML, non-media content is 422", async () => {
    installFetch(() => new Response("%PDF", { headers: { "content-type": "application/pdf" } }));
    const { status, body } = await inspect("https://example.com/a.pdf");
    expect(status).toBe(422);
    expect(String(body.error)).toContain("HTML");
  });

  test("upstream error status is 502", async () => {
    installFetch(() => html("nope", 500));
    const { status, body } = await inspect("https://example.com/");
    expect(status).toBe(502);
    expect(String(body.error)).toContain("500");
  });
});

describe("GET /api/inspect — Discord sources", () => {
  test("keys tags Discord's way: name before property, content before value, document order kept", async () => {
    installFetch(() =>
      html(`<head>
        <meta name="twitter:title" property="og:title" content="Both">
        <meta property="og:image" value="https://cdn.example.com/v.png">
        <meta property="OG:Title" content="Case kept">
      </head>`),
    );
    const { body } = await inspect("https://example.com/");
    expect(body.tags).toEqual([
      { property: "twitter:title", content: "Both", attr: "name" },
      { property: "og:image", content: "https://cdn.example.com/v.png", attr: "property" },
      { property: "OG:Title", content: "Case kept", attr: "property" },
    ]);
  });

  test("sends Discord's crawler user agent by default and ours on ua=embeddy", async () => {
    const seen: string[] = [];
    installFetch((_url, init) => {
      seen.push(new Headers(init?.headers).get("user-agent") ?? "");
      return html("<title>x</title>");
    });
    await inspect("https://example.com/");
    await app.request(`/api/inspect?url=${encodeURIComponent("https://example.com/")}&ua=embeddy`);
    expect(seen[0]).toContain("Discordbot/2.0");
    expect(seen[1]).toContain("Embeddy/1.0");
  });

  test("captures an inline component embed raw, with its byte size and <head> placement", async () => {
    const payload = '{"component":{"type":17,"components":[{"type":10,"content":"héllo \\u003c"}]}}';
    installFetch(() =>
      html(`<html><head><script id="discord:component-embed" type="application/json">${payload}</script></head>
        <body><script id="discord:component-embed" type="application/json">{}</script></body></html>`),
    );
    const { body } = await inspect("https://example.com/");
    const embeds = body.componentEmbeds as Array<Record<string, unknown>>;
    expect(embeds).toHaveLength(2);
    expect(embeds[0]).toMatchObject({ source: "inline", type: "application/json", inHead: true, raw: payload, truncated: false });
    expect(embeds[0]!.bytes).toBe(new TextEncoder().encode(payload).byteLength);
    expect(embeds[1]).toMatchObject({ source: "inline", inHead: false, raw: "{}" });
  });

  test("fetches a same-site linked component embed with Discord's JSON user agent", async () => {
    let jsonUa = "";
    installFetch((url, init) => {
      if (url === "https://cdn.example.com/embed.json") {
        jsonUa = new Headers(init?.headers).get("user-agent") ?? "";
        return new Response('{"component":{"type":17,"components":[]}}');
      }
      return html(`<head><link rel="discord:component-embed" type="application/json" href="https://cdn.example.com/embed.json"></head>`);
    });
    const { body } = await inspect("https://example.com/post");
    const [linked] = body.componentEmbeds as Array<Record<string, unknown>>;
    expect(linked).toMatchObject({
      source: "linked",
      hostAllowed: true,
      fetchStatus: 200,
      raw: '{"component":{"type":17,"components":[]}}',
    });
    expect(jsonUa).toBe("Discordbot/2.0");
  });

  test("does not fetch a linked component embed on another site", async () => {
    const fetched: string[] = [];
    installFetch((url) => {
      fetched.push(url);
      return html(`<head><link rel="discord:component-embed" type="application/json" href="https://evil.example.net/e.json"></head>`);
    });
    const { body } = await inspect("https://example.com/");
    const [linked] = body.componentEmbeds as Array<Record<string, unknown>>;
    expect(linked).toMatchObject({ hostAllowed: false });
    expect(linked!.raw).toBeUndefined();
    expect(fetched).toEqual(["https://example.com/"]);
  });

  test("reads the first JSON oEmbed link, counts the rest, and survives a failing oEmbed", async () => {
    installFetch((url) => {
      if (url === "https://example.com/oembed?u=1") return new Response('{"type":"link","author_name":"Ann"}');
      if (url === "https://example.com/oembed?u=2") return new Response("x", { status: 500 });
      return html(`<head>
        <link rel="alternate" type="application/json+oembed" href="/oembed?u=1">
        <link rel="alternate" type="application/json+oembed" href="/oembed?u=2">
      </head>`);
    });
    const { status, body } = await inspect("https://example.com/");
    expect(status).toBe(200);
    expect(body.oembed).toMatchObject({
      href: "https://example.com/oembed?u=1",
      count: 2,
      status: 200,
      raw: '{"type":"link","author_name":"Ann"}',
    });

    installFetch((url) =>
      url.includes("oembed")
        ? new Response("x", { status: 500 })
        : html(`<head><link type="application/json+oembed" href="/oembed"></head>`),
    );
    const failed = await inspect("https://example.com/");
    expect(failed.status).toBe(200);
    expect(failed.body.oembed).toMatchObject({ status: 500, error: "HTTP 500" });
  });

  test("collects JSON-LD blocks and fetch facts (redirect chain, disposition)", async () => {
    installFetch((url) => {
      if (url === "https://example.com/a") {
        return new Response(null, { status: 302, headers: { location: "/b" } });
      }
      return new Response(
        `<head><script type="application/ld+json">{"@type":"VideoObject","name":"V"}</script></head>`,
        { headers: { "content-type": "text/html", "content-disposition": "attachment" } },
      );
    });
    const { body } = await inspect("https://example.com/a");
    expect(body.jsonLd).toEqual([{ raw: '{"@type":"VideoObject","name":"V"}', bytes: 34, truncated: false }]);
    expect(body.fetch).toMatchObject({
      requestedUrl: "https://example.com/a",
      redirects: ["https://example.com/a"],
      userAgent: "discord",
      contentDisposition: "attachment",
      readCapped: false,
    });
  });

  test("an oversized inline payload is cut at the cap and flagged", async () => {
    const big = `{"component":{"type":17,"components":[{"type":10,"content":"${"a".repeat(MAX_EMBED_JSON_BYTES)}"}]}}`;
    installFetch(() => html(`<head><script id="discord:component-embed" type="application/json">${big}</script></head>`));
    const { body } = await inspect("https://example.com/");
    const [inline] = body.componentEmbeds as Array<{ raw: string; bytes: number; truncated: boolean }>;
    expect(inline!.truncated).toBe(true);
    expect(inline!.bytes).toBeGreaterThan(MAX_EMBED_JSON_BYTES);
    expect(inline!.raw.length).toBeLessThanOrEqual(MAX_EMBED_JSON_BYTES);
  });
});

describe("isLinkedEmbedHostAllowed", () => {
  test("same host, subdomain and parent domain pass; siblings, relative and other sites fail", () => {
    const page = "https://www.example.com/post";
    expect(isLinkedEmbedHostAllowed("https://www.example.com/e.json", page)).toBe(true);
    expect(isLinkedEmbedHostAllowed("https://cdn.www.example.com/e.json", page)).toBe(true);
    expect(isLinkedEmbedHostAllowed("https://example.com/e.json", page)).toBe(true);
    expect(isLinkedEmbedHostAllowed("https://cdn.example.com/e.json", page)).toBe(false);
    expect(isLinkedEmbedHostAllowed("/e.json", page)).toBe(false);
    expect(isLinkedEmbedHostAllowed("https://notexample.com/e.json", "https://example.com/")).toBe(false);
    expect(isLinkedEmbedHostAllowed("ftp://example.com/e.json", "https://example.com/")).toBe(false);
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

describe("decodeHtmlEntities", () => {
  test("numeric, hex and named references; invalid code points become U+FFFD", () => {
    expect(decodeHtmlEntities("&#65;&#x42;&eacute;&nbsp;")).toBe("ABé ");
    expect(decodeHtmlEntities("&#0;&#xD800;")).toBe("��");
    expect(decodeHtmlEntities("a & b &amp c")).toBe("a & b &amp c");
    expect(decodeHtmlEntities("plain")).toBe("plain");
  });
});
