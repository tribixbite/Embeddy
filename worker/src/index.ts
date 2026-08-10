/**
 * Embeddy API Worker — Cloudflare Worker with Hono router.
 *
 * Endpoints:
 *   GET  /api/inspect?url=...  — Fetch a URL and extract OG/Twitter meta tags
 *   POST /api/upload           — Relay file upload to 0x0.st or catbox.moe
 *
 * Security:
 *   - CORS restricted to embeddy.link origins
 *   - SSRF protection blocks private/reserved IP ranges, revalidated on every
 *     redirect hop (redirects are followed manually, not by fetch)
 *   - Request/response size caps on both endpoints
 *
 * NOT handled here: rate limiting. Configure it as a Cloudflare Rate Limiting
 * rule on the api.embeddy.link route — an in-Worker counter would be per-isolate
 * and therefore trivially bypassed.
 */

import { Hono } from "hono";
import { cors } from "hono/cors";
import { MAX_REDIRECTS, isAllowedUrl, safeFetch } from "./ssrf";

type Bindings = {
  // Add KV/D1/R2 bindings here if needed later
};

const app = new Hono<{ Bindings: Bindings }>();

// ── CORS ────────────────────────────────────────────────────────────────────

app.use(
  "/api/*",
  cors({
    origin: [
      "https://embeddy.link",
      "http://localhost:4321",  // Astro dev server
      "http://localhost:3000",
    ],
    allowMethods: ["GET", "POST", "OPTIONS"],
    allowHeaders: ["Content-Type"],
    maxAge: 86400,
  }),
);

// ── Limits ──────────────────────────────────────────────────────────────────

/** Stop reading upstream HTML after this many bytes (meta tags live in <head>). */
const MAX_INSPECT_BYTES = 2 * 1024 * 1024; // 2 MB

/** Largest file the relay will accept, below the Workers request body limit. */
const MAX_UPLOAD_BYTES = 95 * 1024 * 1024; // 95 MB

// ── /api/inspect ─────────────────────────────────────────────────────────────

interface MetaTag {
  property: string;
  content: string;
}

interface InspectResult {
  url: string;
  status: number;
  title: string;
  tags: MetaTag[];
  ogImage?: string;
  ogTitle?: string;
  ogDescription?: string;
  ogSiteName?: string;
  twitterCard?: string;
  themeColor?: string;
  favicon?: string;
}

app.get("/api/inspect", async (c) => {
  const url = c.req.query("url");
  if (!url) {
    return c.json({ error: "Missing 'url' query parameter" }, 400);
  }

  if (!isAllowedUrl(url)) {
    return c.json({ error: "URL not allowed (private/reserved address)" }, 403);
  }

  try {
    const tags: MetaTag[] = [];
    let title = "";

    // Fetch with HTMLRewriter to extract meta tags. Redirects are followed
    // manually so each hop is re-validated against the SSRF blocklist.
    const { response, finalUrl } = await safeFetch(url, {
      headers: {
        "User-Agent": "Embeddy/1.0 (metadata inspector; +https://embeddy.link)",
        Accept: "text/html,application/xhtml+xml",
      },
    });

    if (!response.ok) {
      return c.json(
        { error: `Upstream returned HTTP ${response.status}` },
        502,
      );
    }

    const contentType = response.headers.get("content-type") ?? "";
    if (!contentType.includes("text/html") && !contentType.includes("xhtml")) {
      return c.json(
        { error: "URL did not return HTML content" },
        422,
      );
    }

    // Use HTMLRewriter to stream-parse meta tags
    const rewriter = new HTMLRewriter()
      .on("title", {
        text(chunk) {
          // Cap so a pathological document can't grow this unboundedly
          if (title.length < 1024) title += chunk.text;
        },
      })
      .on('meta[property]', {
        element(el) {
          const prop = el.getAttribute("property");
          const content = el.getAttribute("content");
          if (prop && content) {
            tags.push({ property: prop, content });
          }
        },
      })
      .on('meta[name]', {
        element(el) {
          const name = el.getAttribute("name");
          const content = el.getAttribute("content");
          if (name && content) {
            tags.push({ property: name, content });
          }
        },
      })
      // `rel~=` matches a whitespace-separated list, so this covers both
      // rel="icon" and rel="shortcut icon"; `i` makes the value case-insensitive.
      .on('link[rel~="icon" i]', {
        element(el) {
          const href = el.getAttribute("href");
          if (href) {
            tags.push({ property: "favicon", content: resolveUrl(href, finalUrl) });
          }
        },
      });

    // Process the response through HTMLRewriter. The body must be consumed for
    // handlers to fire, but it is drained with a byte cap so a huge (or endless)
    // upstream response can't exhaust the Worker's memory.
    await drainCapped(rewriter.transform(response), MAX_INSPECT_BYTES);

    // Build structured result from tags
    const findTag = (prop: string) =>
      tags.find((t) => t.property.toLowerCase() === prop)?.content;

    const ogImage = findTag("og:image");
    const result: InspectResult = {
      // Relative URLs resolve against the post-redirect location, not the input
      url: finalUrl,
      status: response.status,
      title: title.trim(),
      tags,
      ogImage: ogImage ? resolveUrl(ogImage, finalUrl) : undefined,
      ogTitle: findTag("og:title"),
      ogDescription: findTag("og:description") ?? findTag("description"),
      ogSiteName: findTag("og:site_name"),
      twitterCard: findTag("twitter:card"),
      themeColor: findTag("theme-color"),
      favicon: findTag("favicon"),
    };

    return c.json(result);
  } catch (err) {
    const message = err instanceof Error ? err.message : "Unknown error";
    return c.json({ error: `Failed to fetch URL: ${message}` }, 502);
  }
});

/**
 * Read a response body until `maxBytes`, then cancel the stream.
 * Nothing is retained — this exists purely to drive HTMLRewriter's handlers.
 */
async function drainCapped(response: Response, maxBytes: number): Promise<void> {
  const body = response.body;
  if (!body) return;

  const reader = body.getReader();
  let read = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      read += value?.byteLength ?? 0;
      if (read >= maxBytes) break;
    }
  } finally {
    await reader.cancel().catch(() => {});
  }
}

/** Resolve a potentially relative URL against a base URL */
function resolveUrl(href: string, base: string): string {
  try {
    return new URL(href, base).toString();
  } catch {
    return href;
  }
}

/** Human-readable megabytes for error messages */
function formatMb(bytes: number): string {
  return `${(bytes / (1024 * 1024)).toFixed(0)} MB`;
}

/** Duck-type a FormData entry as a Blob/File (has a byte size and can be streamed). */
function isBlobLike(value: unknown): value is { size: number } {
  return (
    typeof value === "object" &&
    value !== null &&
    typeof (value as { size?: unknown }).size === "number" &&
    typeof (value as { arrayBuffer?: unknown }).arrayBuffer === "function"
  );
}

// ── /api/upload ──────────────────────────────────────────────────────────────

/** Supported upload hosts, their endpoints, and their published size limits */
const UPLOAD_HOSTS: Record<
  string,
  { url: string; fileField: string; maxBytes: number }
> = {
  "0x0.st": {
    url: "https://0x0.st",
    fileField: "file",
    maxBytes: 512 * 1024 * 1024,
  },
  "catbox.moe": {
    url: "https://catbox.moe/user/api.php",
    fileField: "fileToUpload",
    maxBytes: 200 * 1024 * 1024,
  },
};

app.post("/api/upload", async (c) => {
  try {
    // Reject oversized bodies before buffering the multipart payload
    const declaredLength = Number(c.req.header("content-length") ?? 0);
    if (declaredLength > MAX_UPLOAD_BYTES) {
      return c.json(
        {
          error: `File too large — the relay accepts up to ${formatMb(MAX_UPLOAD_BYTES)}`,
        },
        413,
      );
    }

    const formData = await c.req.formData();
    // Hono types FormData entries as strings, so narrow the Blob case at runtime.
    const entry: unknown = formData.get("file");
    const host = formData.get("host")?.toString() ?? "0x0.st";

    if (!isBlobLike(entry)) {
      return c.json({ error: "Missing 'file' in form data" }, 400);
    }
    const file = entry;

    const hostConfig = UPLOAD_HOSTS[host];
    if (!hostConfig) {
      return c.json({ error: `Unsupported host: ${host}` }, 400);
    }

    const limit = Math.min(hostConfig.maxBytes, MAX_UPLOAD_BYTES);
    if (file.size > limit) {
      return c.json(
        {
          error:
            `File is ${formatMb(file.size)} but the limit for ${host} ` +
            `via this relay is ${formatMb(limit)}`,
        },
        413,
      );
    }

    // Build the upstream form data
    const upstreamForm = new FormData();

    if (host === "catbox.moe") {
      upstreamForm.append("reqtype", "fileupload");
    }
    upstreamForm.append(hostConfig.fileField, file as unknown as Blob);

    // Relay to upstream host
    const response = await fetch(hostConfig.url, {
      method: "POST",
      body: upstreamForm,
    });

    if (!response.ok) {
      const text = await response.text();
      return c.json(
        { error: `Upload host returned HTTP ${response.status}: ${text}` },
        502,
      );
    }

    const resultUrl = (await response.text()).trim();

    // Validate we got a URL back
    if (!resultUrl.startsWith("http")) {
      return c.json(
        { error: `Unexpected response from host: ${resultUrl}` },
        502,
      );
    }

    return c.json({ url: resultUrl });
  } catch (err) {
    const message = err instanceof Error ? err.message : "Unknown error";
    return c.json({ error: `Upload failed: ${message}` }, 500);
  }
});

// ── Health check ─────────────────────────────────────────────────────────────

app.get("/health", (c) => {
  return c.json({
    status: "ok",
    version: "0.1.0",
    limits: {
      maxInspectBytes: MAX_INSPECT_BYTES,
      maxUploadBytes: MAX_UPLOAD_BYTES,
      maxRedirects: MAX_REDIRECTS,
    },
  });
});

// ── 404 fallback ─────────────────────────────────────────────────────────────

app.notFound((c) => {
  return c.json({ error: "Not found" }, 404);
});

// ── Export ────────────────────────────────────────────────────────────────────

export default app;
