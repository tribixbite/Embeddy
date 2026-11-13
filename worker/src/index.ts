/**
 * Embeddy API Worker — Cloudflare Worker with Hono router.
 *
 * Endpoints:
 *   GET  /api/inspect?url=...&ua=discord|embeddy
 *                              — Fetch a URL the way Discord's unfurler does and
 *                                return its meta tags, oEmbed document,
 *                                component-embed payloads and JSON-LD blocks
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
import { BlockedRequestError, MAX_REDIRECTS, isAllowedUrl, safeFetch } from "./ssrf";
import { decodeHtmlEntities } from "./html-entities";
import {
  type CrawlerIdentity,
  fetchTextCapped,
  isLinkedEmbedHostAllowed,
  pageUserAgent,
  parseCrawlerIdentity,
} from "./discord-sources";
import {
  INSPECT_TIMEOUT_MS,
  MAX_EMBED_JSON_BYTES,
  MAX_ERROR_TEXT_CHARS,
  MAX_JSONLD_BLOCKS,
  MAX_JSONLD_BYTES,
  MAX_OEMBED_BYTES,
  MAX_TAGS,
  MAX_TAG_CONTENT_CHARS,
  MAX_TAG_NAME_CHARS,
  SUBFETCH_TIMEOUT_MS,
  UPLOAD_TIMEOUT_MS,
  drainCapped,
  isTimeoutError,
  truncate,
} from "./limits";

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

/**
 * Largest file the relay will accept. The relay holds TWO in-memory copies of
 * the payload (formData() materializes the request, and the rebuilt upstream
 * FormData is serialized again), so the cap must keep 2x + overhead under the
 * 128 MB Workers isolate memory limit. Must stay in sync with RELAY_MAX_BYTES
 * in site/src/lib/upload/types.ts.
 */
const MAX_UPLOAD_BYTES = 50 * 1024 * 1024; // 50 MB

// ── /api/inspect ─────────────────────────────────────────────────────────────

/**
 * One <meta> tag, keyed the way Discord keys it: the key comes from `name`
 * when present, otherwise `property`; the value from `content`, otherwise
 * `value`. Document order is preserved because Discord's first-wins (title,
 * description, site name) and last-wins (layout, color, date) rules depend on it.
 */
interface MetaTag {
  property: string;
  content: string;
  /** Which attribute supplied the key (a link-derived entry such as favicon has none) */
  attr?: "name" | "property";
}

/** Facts about the page fetch that decide whether Discord previews it at all. */
interface FetchInfo {
  /** The URL as requested, before redirects */
  requestedUrl: string;
  /** Every URL that answered with a redirect, in order */
  redirects: string[];
  /** Crawler identity used for the page request */
  userAgent: CrawlerIdentity;
  contentType: string;
  contentDisposition?: string;
  /** Declared Content-Length, if the server sent one */
  contentLength?: number;
  /** HTML bytes read before the inspector's own cap */
  bytesRead: number;
  /** True when the inspector stopped reading at MAX_INSPECT_BYTES */
  readCapped: boolean;
  /** Wall-clock time for the page fetch (headers + body), in ms */
  ms: number;
}

/** A component-embed payload found on the page (inline script or linked JSON). */
interface ComponentEmbedSource {
  source: "inline" | "linked";
  /** The element's `type` attribute as written */
  type: string | null;
  /** Inline only: whether the script sat inside <head> */
  inHead?: boolean;
  /** Linked only: href as written */
  href?: string;
  /** Linked only: whether href passes Discord's same-site rule */
  hostAllowed?: boolean;
  /** Raw payload text (cut at MAX_EMBED_JSON_BYTES) */
  raw?: string;
  /** Payload size in bytes (stops counting one past the cap) */
  bytes: number;
  truncated: boolean;
  /** Linked only: secondary fetch outcome */
  fetchStatus?: number;
  fetchMs?: number;
  error?: string;
}

/** The oEmbed document Discord would read (the first JSON discovery link). */
interface OEmbedSource {
  href: string;
  /** How many JSON oEmbed links the page declares — only the first is used */
  count: number;
  status: number;
  ms: number;
  raw?: string;
  bytes: number;
  truncated: boolean;
  error?: string;
}

/** Raw text of one <script type="application/ld+json"> block. */
interface JsonLdBlock {
  raw: string;
  bytes: number;
  truncated: boolean;
}

interface InspectResult {
  url: string;
  status: number;
  title: string;
  tags: MetaTag[];
  fetch: FetchInfo;
  /** Set when the URL is an image/video file — Discord previews it with no metadata */
  direct?: { kind: "image" | "video" };
  oembed?: OEmbedSource;
  /** Every component-embed candidate, inline first, in document order */
  componentEmbeds: ComponentEmbedSource[];
  jsonLd: JsonLdBlock[];
  // Convenience fields kept for older clients
  ogImage?: string;
  ogTitle?: string;
  ogDescription?: string;
  ogSiteName?: string;
  twitterCard?: string;
  themeColor?: string;
  favicon?: string;
}

/** Discord accepts either MIME type on the component-embed elements. */
const COMPONENT_EMBED_ID = "discord:component-embed";

/** Content types Discord treats as an HTML page. */
function isHtmlContentType(contentType: string): boolean {
  const ct = contentType.toLowerCase();
  return ct.includes("text/html") || ct.includes("application/xhtml+xml");
}

const utf8 = new TextEncoder();

app.get("/api/inspect", async (c) => {
  const url = c.req.query("url");
  if (!url) {
    return c.json({ error: "Missing 'url' query parameter" }, 400);
  }

  if (!isAllowedUrl(url)) {
    return c.json({ error: "URL not allowed (private/reserved address)" }, 403);
  }

  const identity = parseCrawlerIdentity(c.req.query("ua"));

  try {
    const tags: MetaTag[] = [];
    let title = "";
    /** Record a tag within the count/length caps (L1) */
    const addTag = (property: string, content: string, attr?: MetaTag["attr"]) => {
      if (tags.length >= MAX_TAGS) return;
      tags.push({
        property: truncate(property, MAX_TAG_NAME_CHARS),
        content: truncate(content, MAX_TAG_CONTENT_CHARS),
        ...(attr ? { attr } : {}),
      });
    };

    // Fetch the page. Redirects are followed manually so each hop is
    // re-validated against the SSRF blocklist.
    const started = Date.now();
    const { response, finalUrl, redirects } = await safeFetch(url, {
      headers: {
        "User-Agent": pageUserAgent(identity),
        Accept: "text/html,application/xhtml+xml",
      },
      signal: AbortSignal.timeout(INSPECT_TIMEOUT_MS),
    });

    if (!response.ok) {
      // Never read — release the connection instead of leaving it pinned
      await response.body?.cancel().catch(() => {});
      return c.json(
        { error: `Upstream returned HTTP ${response.status}` },
        502,
      );
    }

    const contentType = response.headers.get("content-type") ?? "";
    const declaredLength = Number(response.headers.get("content-length"));
    const fetchInfo: FetchInfo = {
      requestedUrl: url,
      redirects,
      userAgent: identity,
      contentType,
      contentDisposition: response.headers.get("content-disposition") ?? undefined,
      contentLength: Number.isFinite(declaredLength) && declaredLength > 0 ? declaredLength : undefined,
      bytesRead: 0,
      readCapped: false,
      ms: 0,
    };

    if (!isHtmlContentType(contentType)) {
      await response.body?.cancel().catch(() => {});
      // A link straight to an image or video file still unfurls on Discord
      // (media preview, no metadata), so it is a result, not an error.
      const mediaKind = contentType.startsWith("image/")
        ? "image"
        : contentType.startsWith("video/")
          ? "video"
          : null;
      if (!mediaKind) {
        return c.json(
          { error: "URL did not return HTML content" },
          422,
        );
      }
      fetchInfo.ms = Date.now() - started;
      const direct: InspectResult = {
        url: finalUrl,
        status: response.status,
        title: "",
        tags: [],
        fetch: fetchInfo,
        direct: { kind: mediaKind },
        componentEmbeds: [],
        jsonLd: [],
      };
      return c.json(direct);
    }

    // ── Streaming extraction ──────────────────────────────────────────────
    let inHead = false;
    let titleCount = 0;
    let titleDone = false;
    let oembedHref: string | undefined;
    let oembedCount = 0;
    const componentEmbeds: ComponentEmbedSource[] = [];
    const jsonLd: JsonLdBlock[] = [];
    /** The script whose text chunks are currently streaming, if any */
    let textTarget: { raw: string; bytes: number; truncated: boolean; cap: number } | null = null;

    const collectText = {
      text(chunk: Text) {
        if (!textTarget || !chunk.text) return;
        const size = utf8.encode(chunk.text).byteLength;
        // Count one byte past the cap so an oversized payload reads as truncated
        if (textTarget.bytes > textTarget.cap) return;
        textTarget.bytes += size;
        if (textTarget.bytes > textTarget.cap) {
          textTarget.truncated = true;
          return;
        }
        textTarget.raw += chunk.text;
      },
    };

    const rewriter = new HTMLRewriter()
      .on("head", {
        element(el) {
          inHead = true;
          el.onEndTag(() => {
            inHead = false;
          });
        },
      })
      .on("body", {
        element() {
          // Covers documents that never close <head> explicitly
          inHead = false;
        },
      })
      .on("title", {
        // Only the first <title> is the document title — later ones are
        // usually inline-SVG icon titles in <body> ("…GitHubLinkedIn").
        element(el) {
          titleCount++;
          if (titleCount === 1) el.onEndTag(() => { titleDone = true; });
        },
        text(chunk) {
          // Cap so a pathological document can't grow this unboundedly
          if (titleCount === 1 && !titleDone && title.length < 1024) title += chunk.text;
        },
      })
      .on("meta", {
        element(el) {
          // Discord: key from `name` if present, else `property`; value from
          // `content` if present, else `value`. Keys stay case-sensitive.
          const name = el.getAttribute("name");
          const property = el.getAttribute("property");
          const key = name ?? property;
          if (!key) return;
          const value = el.getAttribute("content") ?? el.getAttribute("value") ?? "";
          // HTMLRewriter returns attribute values undecoded ("Tom &amp; Jerry")
          addTag(key, decodeHtmlEntities(value), name !== null ? "name" : "property");
        },
      })
      // `rel~=` matches a whitespace-separated list, so this covers both
      // rel="icon" and rel="shortcut icon"; `i` makes the value case-insensitive.
      .on('link[rel~="icon" i]', {
        element(el) {
          const href = el.getAttribute("href");
          if (href) addTag("favicon", resolveUrl(decodeHtmlEntities(href), finalUrl));
        },
      })
      .on('link[type="application/json+oembed" i]', {
        element(el) {
          const href = el.getAttribute("href");
          if (!href) return;
          oembedCount++;
          // Discord uses the first matching link. Decoding matters here:
          // "?format=json&amp;url=…" would otherwise request the wrong URL.
          oembedHref ??= resolveUrl(decodeHtmlEntities(href), finalUrl);
        },
      })
      .on(`link[rel~="${COMPONENT_EMBED_ID}"]`, {
        element(el) {
          const href = decodeHtmlEntities(el.getAttribute("href") ?? "");
          componentEmbeds.push({
            source: "linked",
            type: el.getAttribute("type"),
            href,
            hostAllowed: isLinkedEmbedHostAllowed(href, finalUrl),
            bytes: 0,
            truncated: false,
          });
        },
      })
      .on("script", {
        element(el) {
          textTarget = null;
          const id = el.getAttribute("id");
          const type = el.getAttribute("type");
          if (id === COMPONENT_EMBED_ID) {
            const entry: ComponentEmbedSource = {
              source: "inline",
              type,
              inHead,
              raw: "",
              bytes: 0,
              truncated: false,
            };
            componentEmbeds.push(entry);
            textTarget = { raw: "", bytes: 0, truncated: false, cap: MAX_EMBED_JSON_BYTES };
            // Copy the streamed text back when the element closes
            const target = textTarget;
            el.onEndTag(() => {
              entry.raw = target.raw;
              entry.bytes = target.bytes;
              entry.truncated = target.truncated;
            });
          } else if (type?.trim().toLowerCase() === "application/ld+json" && jsonLd.length < MAX_JSONLD_BLOCKS) {
            const block: JsonLdBlock = { raw: "", bytes: 0, truncated: false };
            jsonLd.push(block);
            textTarget = { raw: "", bytes: 0, truncated: false, cap: MAX_JSONLD_BYTES };
            const target = textTarget;
            el.onEndTag(() => {
              block.raw = target.raw;
              block.bytes = target.bytes;
              block.truncated = target.truncated;
            });
          }
        },
        ...collectText,
      });

    // Process the response through HTMLRewriter. The body must be consumed for
    // handlers to fire, but it is drained with a byte cap so a huge (or endless)
    // upstream response can't exhaust the Worker's memory. The rewriter changes
    // nothing, so its streamed output is byte-for-byte the input and the drained
    // count is the page size. (The count can't be taken on the way in: Bun's
    // HTMLRewriter, the test runtime, rejects JS-constructed stream bodies.
    // Bun also reports 0 for its own network-fetch bodies — a local-dev quirk.)
    fetchInfo.bytesRead = await drainCapped(rewriter.transform(response), MAX_INSPECT_BYTES);
    fetchInfo.readCapped = fetchInfo.bytesRead >= MAX_INSPECT_BYTES;
    fetchInfo.ms = Date.now() - started;

    // ── Secondary documents, fetched in parallel ──────────────────────────
    const linked = componentEmbeds.find((e) => e.source === "linked" && e.hostAllowed);
    const [oembedFetch, linkedFetch] = await Promise.all([
      oembedHref ? fetchTextCapped(oembedHref, MAX_OEMBED_BYTES, SUBFETCH_TIMEOUT_MS) : null,
      linked?.href ? fetchTextCapped(linked.href, MAX_EMBED_JSON_BYTES, SUBFETCH_TIMEOUT_MS) : null,
    ]);

    let oembed: OEmbedSource | undefined;
    if (oembedHref && oembedFetch) {
      oembed = {
        href: oembedHref,
        count: oembedCount,
        status: oembedFetch.status,
        ms: oembedFetch.ms,
        raw: oembedFetch.text,
        bytes: oembedFetch.bytes,
        truncated: oembedFetch.truncated,
        error: oembedFetch.error,
      };
    }
    if (linked && linkedFetch) {
      linked.raw = linkedFetch.text;
      linked.bytes = linkedFetch.bytes;
      linked.truncated = linkedFetch.truncated;
      linked.fetchStatus = linkedFetch.status;
      linked.fetchMs = linkedFetch.ms;
      linked.error = linkedFetch.error;
    }

    // Convenience fields (case-insensitive, first match) for older clients
    const findTag = (prop: string) =>
      tags.find((t) => t.property.toLowerCase() === prop)?.content || undefined;

    const ogImage = findTag("og:image");
    const result: InspectResult = {
      // Relative URLs resolve against the post-redirect location, not the input
      url: finalUrl,
      status: response.status,
      title: decodeHtmlEntities(title).trim(),
      tags,
      fetch: fetchInfo,
      oembed,
      componentEmbeds,
      jsonLd,
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
    // Policy refusals (blocked redirect target, hostname resolving into
    // private space) are the same contract as the up-front check: 403.
    if (err instanceof BlockedRequestError) {
      return c.json({ error: err.message }, 403);
    }
    if (isTimeoutError(err)) {
      return c.json(
        { error: `Upstream did not respond within ${INSPECT_TIMEOUT_MS / 1000}s` },
        504,
      );
    }
    const message = err instanceof Error ? err.message : "Unknown error";
    return c.json({ error: `Failed to fetch URL: ${truncate(message, MAX_ERROR_TEXT_CHARS)}` }, 502);
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
    // Reject oversized bodies before buffering the multipart payload. A
    // missing/unparseable Content-Length (chunked encoding) would bypass this
    // pre-buffer check entirely, so a declared length is mandatory — browser
    // FormData uploads always send one.
    const declaredHeader = c.req.header("content-length");
    const declaredLength = Number(declaredHeader);
    if (!declaredHeader || !Number.isFinite(declaredLength) || declaredLength < 0) {
      return c.json(
        { error: "Content-Length request header is required" },
        411,
      );
    }
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
      signal: AbortSignal.timeout(UPLOAD_TIMEOUT_MS),
    });

    if (!response.ok) {
      // Hosts answer errors with whole HTML pages — echo only the start (L1)
      const text = truncate((await response.text()).trim(), MAX_ERROR_TEXT_CHARS);
      return c.json(
        { error: `Upload host returned HTTP ${response.status}: ${text}` },
        502,
      );
    }

    const resultUrl = (await response.text()).trim();

    // Validate we got a URL back
    if (!resultUrl.startsWith("http")) {
      return c.json(
        { error: `Unexpected response from host: ${truncate(resultUrl, MAX_ERROR_TEXT_CHARS)}` },
        502,
      );
    }

    return c.json({ url: resultUrl });
  } catch (err) {
    if (isTimeoutError(err)) {
      return c.json(
        { error: `Upload host did not respond within ${UPLOAD_TIMEOUT_MS / 1000}s` },
        504,
      );
    }
    const message = err instanceof Error ? err.message : "Unknown error";
    return c.json({ error: `Upload failed: ${truncate(message, MAX_ERROR_TEXT_CHARS)}` }, 500);
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
      inspectTimeoutMs: INSPECT_TIMEOUT_MS,
      uploadTimeoutMs: UPLOAD_TIMEOUT_MS,
      maxTags: MAX_TAGS,
    },
  });
});

// ── 404 fallback ─────────────────────────────────────────────────────────────

app.notFound((c) => {
  return c.json({ error: "Not found" }, 404);
});

// ── Export ────────────────────────────────────────────────────────────────────

export default app;
