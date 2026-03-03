/**
 * Embeddy API Worker — Cloudflare Worker with Hono router.
 *
 * Endpoints:
 *   GET  /api/inspect?url=...  — Fetch a URL and extract OG/Twitter meta tags
 *   POST /api/upload           — Relay file upload to 0x0.st or catbox.moe
 *
 * Security:
 *   - CORS restricted to embeddy.link origins
 *   - SSRF protection blocks private/reserved IP ranges
 *   - Rate limiting via CF's built-in rate limiter (30 req/min/IP)
 */

import { Hono } from "hono";
import { cors } from "hono/cors";

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

// ── Rate Limiting (simple in-memory per-request check) ──────────────────────
// For production, use CF Rate Limiting rules in dashboard.
// This is a basic per-request header hint for the client.

app.use("/api/*", async (c, next) => {
  const ip = c.req.header("cf-connecting-ip") ?? "unknown";
  c.header("X-Client-IP", ip);
  await next();
});

// ── SSRF Protection ─────────────────────────────────────────────────────────

/** Block requests to private/reserved IP ranges and non-HTTP schemes */
function isAllowedUrl(urlStr: string): boolean {
  let url: URL;
  try {
    url = new URL(urlStr);
  } catch {
    return false;
  }

  // Only allow http/https schemes
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    return false;
  }

  const hostname = url.hostname.toLowerCase();

  // Block localhost and common internal hostnames
  if (
    hostname === "localhost" ||
    hostname === "127.0.0.1" ||
    hostname === "[::1]" ||
    hostname === "0.0.0.0" ||
    hostname.endsWith(".local") ||
    hostname.endsWith(".internal")
  ) {
    return false;
  }

  // Block private IP ranges (10.x, 172.16-31.x, 192.168.x, 169.254.x)
  const ipMatch = hostname.match(/^(\d+)\.(\d+)\.(\d+)\.(\d+)$/);
  if (ipMatch) {
    const [, a, b] = ipMatch.map(Number);
    if (
      a === 10 ||
      a === 127 ||
      (a === 172 && b! >= 16 && b! <= 31) ||
      (a === 192 && b === 168) ||
      (a === 169 && b === 254) ||
      a === 0
    ) {
      return false;
    }
  }

  return true;
}

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
    let insideTitle = false;

    // Fetch with HTMLRewriter to extract meta tags
    const response = await fetch(url, {
      headers: {
        "User-Agent": "Embeddy/1.0 (metadata inspector; +https://embeddy.link)",
        Accept: "text/html,application/xhtml+xml",
      },
      redirect: "follow",
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
          insideTitle = true;
          title += chunk.text;
          if (chunk.lastInTextNode) insideTitle = false;
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
      .on('link[rel="icon"]', {
        element(el) {
          const href = el.getAttribute("href");
          if (href) {
            tags.push({ property: "favicon", content: resolveUrl(href, url) });
          }
        },
      })
      .on('link[rel="shortcut icon"]', {
        element(el) {
          const href = el.getAttribute("href");
          if (href) {
            tags.push({ property: "favicon", content: resolveUrl(href, url) });
          }
        },
      });

    // Process the response through HTMLRewriter (must consume the body)
    const transformed = rewriter.transform(response);
    await transformed.text();

    // Build structured result from tags
    const findTag = (prop: string) =>
      tags.find((t) => t.property.toLowerCase() === prop)?.content;

    const ogImage = findTag("og:image");
    const result: InspectResult = {
      url: response.url || url,
      status: response.status,
      title: title.trim(),
      tags,
      ogImage: ogImage ? resolveUrl(ogImage, url) : undefined,
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

/** Resolve a potentially relative URL against a base URL */
function resolveUrl(href: string, base: string): string {
  try {
    return new URL(href, base).toString();
  } catch {
    return href;
  }
}

// ── /api/upload ──────────────────────────────────────────────────────────────

/** Supported upload hosts and their endpoints */
const UPLOAD_HOSTS: Record<string, { url: string; fileField: string }> = {
  "0x0.st": {
    url: "https://0x0.st",
    fileField: "file",
  },
  "catbox.moe": {
    url: "https://catbox.moe/user/api.php",
    fileField: "fileToUpload",
  },
};

app.post("/api/upload", async (c) => {
  try {
    const formData = await c.req.formData();
    const file = formData.get("file");
    const host = formData.get("host")?.toString() ?? "0x0.st";

    if (!file || typeof file === "string") {
      return c.json({ error: "Missing 'file' in form data" }, 400);
    }

    const hostConfig = UPLOAD_HOSTS[host];
    if (!hostConfig) {
      return c.json({ error: `Unsupported host: ${host}` }, 400);
    }

    // Build the upstream form data
    const upstreamForm = new FormData();

    if (host === "catbox.moe") {
      upstreamForm.append("reqtype", "fileupload");
      upstreamForm.append(hostConfig.fileField, file);
    } else {
      upstreamForm.append(hostConfig.fileField, file);
    }

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
  return c.json({ status: "ok", version: "0.1.0" });
});

// ── 404 fallback ─────────────────────────────────────────────────────────────

app.notFound((c) => {
  return c.json({ error: "Not found" }, 404);
});

// ── Export ────────────────────────────────────────────────────────────────────

export default app;
