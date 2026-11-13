/**
 * Discord link-preview resolution: given what the Worker fetched, work out
 * the preview Discord would build and every documented rule the page passes
 * or breaks.
 *
 * Source of truth: https://docs.discord.com/developers/link-previews/overview
 * (tag reference, layouts, images, video, oEmbed, limits) and
 * https://docs.discord.com/developers/link-previews/component-embeds.
 *
 * Where Discord's docs leave an order of precedence open (layout tags that
 * disagree, JSON-LD vs Open Graph, several component-embed elements), the
 * choice made here is commented at the point it is made and the UI labels the
 * result as the inspector's reading, pointing to Discord's Embed Debugger for
 * the authoritative answer.
 */

import type { ComponentEmbedSource, MetaTag, MetadataResult } from "./types";
import {
  COMPONENT_EMBED_MIME_TYPES,
  IMAGE_EXTENSIONS,
  VIDEO_EXTENSIONS,
  type ComponentEmbedValidation,
  urlExtension,
  utf8ByteLength,
  validateComponentEmbed,
} from "./component-embed";

// ── Documented limits ───────────────────────────────────────────────────────

export const DISCORD_LIMITS = {
  titleBytes: 70,
  titleLines: 3,
  descriptionBytes: 350,
  descriptionLines: 20,
  siteNameChars: 256,
  altChars: 4_096,
  urlChars: 2_048,
  imagesPerLink: 4,
  redirects: 5,
  fetchMs: 10_000,
  pageBytes: 50 * 1024 * 1024,
  cacheMinutes: 30,
} as const;

/** Discord's own embed debugger — the authoritative check */
export const DISCORD_EMBED_DEBUGGER_URL = "https://discord.com/developers/embeds";

const IMAGE_MIME_TYPES = ["image/png", "image/gif", "image/jpeg", "image/webp", "image/avif"];
const VIDEO_MIME_TYPES = ["video/mp4", "video/webm", "video/quicktime"];

// ── Result types ────────────────────────────────────────────────────────────

export type CheckStatus = "pass" | "warn" | "fail" | "info";
export type CheckGroup = "fetch" | "text" | "media" | "oembed" | "component";

export interface PreviewCheck {
  group: CheckGroup;
  status: CheckStatus;
  title: string;
  detail?: string;
}

export type PreviewLayout =
  | "none"
  | "text"
  | "thumbnail"
  | "large-image"
  | "photo"
  | "video"
  | "gallery"
  | "rich"
  | "direct-image"
  | "direct-video";

/** Human label for each layout */
export const LAYOUT_LABELS: Record<PreviewLayout, string> = {
  none: "No preview",
  text: "Text only",
  thumbnail: "Text with small image",
  "large-image": "Text with large image",
  photo: "Image only",
  video: "Inline video",
  gallery: "Image gallery",
  rich: "Rich (markdown description)",
  "direct-image": "Direct image link",
  "direct-video": "Direct video link",
};

export interface PreviewImage {
  /** Absolute URL */
  url: string;
  /** The tag that declared it */
  key: string;
  width?: number;
  height?: number;
  type?: string;
  alt?: string;
}

export interface PreviewVideo {
  url: string;
  key: string;
  type?: string;
  width?: number;
  height?: number;
}

/** A text field after Discord's byte/line trimming */
export interface TrimmedText {
  /** What Discord shows */
  text: string;
  /** The value as declared */
  full: string;
  trimmed: boolean;
  bytes: number;
  lines: number;
}

export interface StandardPreview {
  layout: PreviewLayout;
  /** The link the title points at (the shared page) */
  url: string;
  title?: TrimmedText;
  /** Which source supplied the title */
  titleSource?: string;
  description?: TrimmedText;
  descriptionSource?: string;
  /** Description is Discord markdown (og:type rich) */
  markdown: boolean;
  siteName?: string;
  providerUrl?: string;
  author?: { name: string; url?: string };
  /** Images the layout shows (≤ 4) */
  images: PreviewImage[];
  /** Every image Discord would consider */
  allImages: PreviewImage[];
  video?: PreviewVideo;
  /** Accent color as `#rrggbb` (alpha dropped for display) */
  color?: string;
  /** ISO timestamp */
  timestamp?: string;
}

export interface ComponentEmbedReport {
  element: ComponentEmbedSource;
  validation?: ComponentEmbedValidation;
  /** Element-level problems (placement, type attribute, fetch) */
  elementErrors: string[];
  /** True when Discord should render this payload */
  usable: boolean;
}

/** Fields read from the oEmbed document */
export interface OEmbedFields {
  type?: string;
  title?: string;
  authorName?: string;
  authorUrl?: string;
  providerName?: string;
  providerUrl?: string;
  thumbnailUrl?: string;
  thumbnailWidth?: number;
  thumbnailHeight?: number;
}

export interface UnfurlReport {
  preview: StandardPreview;
  /** Every component-embed candidate, assessed */
  componentEmbeds: ComponentEmbedReport[];
  /** The component embed Discord should render instead of `preview`, if any */
  activeComponentEmbed?: ComponentEmbedReport;
  oembed?: OEmbedFields;
  checks: PreviewCheck[];
  /** Page URL with a fresh query value, to defeat Discord's ~30 min cache */
  cacheBustUrl: string;
}

// ── Small helpers ───────────────────────────────────────────────────────────

/**
 * Trim a string to Discord's byte and line limits without splitting a code
 * point; a trimmed value gets an ellipsis.
 */
export function trimForDiscord(full: string, maxBytes: number, maxLines: number): TrimmedText {
  const lines = full.split("\n");
  let text = lines.length > maxLines ? lines.slice(0, maxLines).join("\n") : full;
  let trimmed = lines.length > maxLines;
  if (utf8ByteLength(text) > maxBytes) {
    trimmed = true;
    // Walk code points so a multi-byte character is never cut in half;
    // reserve 3 bytes for the ellipsis.
    let out = "";
    let used = 0;
    for (const ch of text) {
      const size = utf8ByteLength(ch);
      if (used + size > maxBytes - 3) break;
      out += ch;
      used += size;
    }
    text = out;
  }
  return {
    text: trimmed ? `${text.trimEnd()}…` : text,
    full,
    trimmed,
    bytes: utf8ByteLength(full),
    lines: lines.length,
  };
}

/** Positive integer from a tag value, else undefined */
function toDimension(value: unknown): number | undefined {
  const n = typeof value === "number" ? value : Number(String(value ?? "").trim());
  return Number.isInteger(n) && n > 0 ? n : undefined;
}

/**
 * Resolve a media URL the way Discord does: absolute http(s) URLs pass, and
 * relative URLs must start with "/" (anything else is not read).
 */
export function resolveDiscordUrl(value: string, pageUrl: string): string | null {
  const v = value.trim();
  if (/^https?:\/\//i.test(v)) return v;
  if (!v.startsWith("/")) return null;
  try {
    return new URL(v, pageUrl).toString();
  } catch {
    return null;
  }
}

/** RFC 3339 with a time and an offset, as Discord requires for publish dates */
export function isRfc3339(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}[Tt ]\d{2}:\d{2}:\d{2}(\.\d+)?([Zz]|[+-]\d{2}:\d{2})$/.test(value.trim()) &&
    !Number.isNaN(Date.parse(value.trim()));
}

/** Discord accepts `#RRGGBB` or `#RRGGBBAA` only */
export function parseThemeColor(value: string): string | null {
  const m = /^#([0-9a-f]{6})([0-9a-f]{2})?$/i.exec(value.trim());
  return m ? `#${m[1]!.toLowerCase()}` : null;
}

/** Page URL with a cache-busting `v` query value */
export function cacheBustUrl(pageUrl: string, stamp = Date.now()): string {
  try {
    const u = new URL(pageUrl);
    u.searchParams.set("v", stamp.toString(36));
    return u.toString();
  } catch {
    return pageUrl;
  }
}

/** Real meta tags only (the Worker also reports the favicon link as a pseudo-tag) */
function metaTags(tags: MetaTag[]): MetaTag[] {
  return tags.filter((t) => !(t.attr === undefined && t.property === "favicon"));
}

/** First non-empty value among `keys`, in document order */
function firstNonEmpty(tags: MetaTag[], keys: readonly string[]): MetaTag | undefined {
  return tags.find((t) => keys.includes(t.property) && t.content.trim() !== "");
}

/** Last non-empty value among `keys`, in document order */
function lastNonEmpty(tags: MetaTag[], keys: readonly string[]): MetaTag | undefined {
  for (let i = tags.length - 1; i >= 0; i--) {
    const t = tags[i]!;
    if (keys.includes(t.property) && t.content.trim() !== "") return t;
  }
  return undefined;
}

/** Parse a JSON object, or null */
function parseJsonObject(raw: string | undefined): Record<string, unknown> | null {
  if (!raw) return null;
  try {
    const v: unknown = JSON.parse(raw);
    return typeof v === "object" && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

const str = (v: unknown): string | undefined => (typeof v === "string" && v.trim() ? v.trim() : undefined);

/** True when a JSON-LD node's @type is (or includes) VideoObject */
function isVideoObject(node: unknown): node is Record<string, unknown> {
  if (typeof node !== "object" || node === null) return false;
  const type = (node as Record<string, unknown>)["@type"];
  return type === "VideoObject" || (Array.isArray(type) && type.includes("VideoObject"));
}

// ── Main analysis ───────────────────────────────────────────────────────────

/**
 * Work out the Discord preview for an inspected page, and the checks behind it.
 */
export function analyzeUnfurl(result: MetadataResult, now = Date.now()): UnfurlReport {
  const checks: PreviewCheck[] = [];
  const check = (group: CheckGroup, status: CheckStatus, title: string, detail?: string) =>
    checks.push({ group, status, title, ...(detail ? { detail } : {}) });

  const pageUrl = result.url;
  const tags = metaTags(result.tags);
  const fetch = result.fetch;
  /** Set when a documented rule means Discord shows no preview at all */
  const blockers: string[] = [];

  // ── Fetch rules ──
  check("fetch", result.status >= 200 && result.status < 300 ? "pass" : "fail", `HTTP ${result.status}`, "Discord needs a 2xx response.");
  if (fetch) {
    const ct = fetch.contentType || "(none)";
    if (result.direct) {
      check("fetch", "pass", `Direct ${result.direct.kind} link`, `${ct} — Discord shows the media on its own, no metadata needed.`);
    } else {
      check("fetch", "pass", "Served as HTML", ct);
    }
    if (fetch.contentDisposition && /^\s*attachment/i.test(fetch.contentDisposition)) {
      check("fetch", "fail", "Served as a download", `Content-Disposition: ${fetch.contentDisposition} — Discord shows no preview for attachments.`);
      blockers.push("served as a download");
    }
    const hops = fetch.redirects.length;
    check(
      "fetch",
      "pass",
      hops === 0 ? "No redirects" : `${hops} redirect${hops === 1 ? "" : "s"} (limit ${DISCORD_LIMITS.redirects})`,
      hops ? [...fetch.redirects, pageUrl].join(" → ") : undefined,
    );
    check(
      "fetch",
      fetch.ms > DISCORD_LIMITS.fetchMs ? "fail" : fetch.ms > DISCORD_LIMITS.fetchMs * 0.6 ? "warn" : "pass",
      `Page fetched in ${(fetch.ms / 1000).toFixed(2)} s`,
      "Discord's whole fetch — page, image/video measuring and component-embed media — must finish within 10 s. Times here are from Cloudflare's network, not Discord's.",
    );
    if (fetch.contentLength && fetch.contentLength > DISCORD_LIMITS.pageBytes) {
      check("fetch", "fail", "Page is over 50 MB", `Content-Length ${fetch.contentLength.toLocaleString()} bytes.`);
      blockers.push("over 50 MB");
    } else if (fetch.readCapped) {
      check("fetch", "info", "Only the first 2 MB were inspected", "Discord reads up to 50 MB; tags placed after 2 MB aren't shown here.");
    }
    check(
      "fetch",
      "info",
      fetch.userAgent === "discord" ? "Fetched as Discordbot" : "Fetched as Embeddy (not Discordbot)",
      fetch.userAgent === "discord"
        ? "Same user agent Discord sends, so sites that vary by crawler serve what Discord gets."
        : "Some sites serve Discordbot different HTML; switch the crawler to Discordbot to compare.",
    );
  }

  // ── Direct media links ──
  if (result.direct) {
    const layout: PreviewLayout = result.direct.kind === "image" ? "direct-image" : "direct-video";
    const media = { url: pageUrl, key: "url" };
    return {
      preview: {
        layout,
        url: pageUrl,
        markdown: false,
        images: layout === "direct-image" ? [media] : [],
        allImages: layout === "direct-image" ? [media] : [],
        video: layout === "direct-video" ? media : undefined,
      },
      componentEmbeds: [],
      checks,
      cacheBustUrl: cacheBustUrl(pageUrl, now),
    };
  }

  // ── oEmbed ──
  let oembed: OEmbedFields | undefined;
  if (result.oembed) {
    const o = result.oembed;
    if (o.error) {
      check("oembed", "warn", "oEmbed document couldn't be read", `${o.href} — ${o.error}. Discord ignores oEmbed errors.`);
    } else {
      const doc = parseJsonObject(o.raw);
      if (!doc) {
        check("oembed", "warn", "oEmbed document isn't a JSON object", `${o.href} — Discord supports JSON oEmbed only and ignores parse errors.`);
      } else {
        oembed = {
          type: str(doc.type),
          title: str(doc.title),
          authorName: str(doc.author_name),
          authorUrl: str(doc.author_url),
          providerName: str(doc.provider_name),
          providerUrl: str(doc.provider_url),
          thumbnailUrl: str(doc.thumbnail_url),
          thumbnailWidth: toDimension(doc.thumbnail_width),
          thumbnailHeight: toDimension(doc.thumbnail_height),
        };
        check("oembed", "pass", "oEmbed document read", o.href);
        const ignored = ["html", "url", "width", "height", "cache_age"].filter((k) => k in doc);
        if (ignored.length) check("oembed", "info", `Ignored oEmbed fields: ${ignored.join(", ")}`, "Discord doesn't use these.");
      }
    }
    if (o.count > 1) check("oembed", "info", `${o.count} JSON oEmbed links`, "Discord uses only the first.");
  }

  // ── Text fields (first non-empty wins) ──
  const ogTitle = firstNonEmpty(tags, ["og:title", "twitter:title"]);
  const ogDesc = firstNonEmpty(tags, ["og:description", "twitter:description"]);
  let titleValue = ogTitle?.content.trim();
  let titleSource = ogTitle?.property;
  let descValue = ogDesc?.content.trim();
  let descSource = ogDesc?.property;

  if (!ogTitle && !ogDesc) {
    // <title> + <meta name="description"> are used only as a pair, and only
    // when Open Graph/Twitter supply neither field.
    const htmlTitle = result.title.trim();
    const metaDesc = tags.find((t) => t.property === "description" && t.attr === "name")?.content.trim();
    if (htmlTitle && metaDesc) {
      titleValue = htmlTitle;
      titleSource = "<title>";
      descValue = metaDesc;
      descSource = '<meta name="description">';
      check("text", "info", "Using <title> and meta description", "Discord falls back to this pair only when no Open Graph or Twitter title/description exists.");
    } else if (htmlTitle || metaDesc) {
      check("text", "warn", "<title> / meta description fallback not used", "Discord reads them only as a pair — this page has just one of the two.");
    }
  }
  if (!ogTitle && oembed?.title) {
    // oEmbed fills the title only when Open Graph/Twitter gave none
    titleValue = oembed.title;
    titleSource = "oEmbed title";
  }

  const ogType = lastNonEmpty(tags, ["og:type"])?.content.trim();
  const twitterCard = lastNonEmpty(tags, ["twitter:card"])?.content.trim();
  const isRich = ogType === "rich";

  const title = titleValue ? trimForDiscord(titleValue, DISCORD_LIMITS.titleBytes, DISCORD_LIMITS.titleLines) : undefined;
  const description = descValue
    ? trimForDiscord(descValue, DISCORD_LIMITS.descriptionBytes, DISCORD_LIMITS.descriptionLines)
    : undefined;

  if (title) {
    check(
      "text",
      title.trimmed ? "warn" : "pass",
      title.trimmed ? `Title trimmed (${title.bytes} bytes, ${title.lines} line${title.lines === 1 ? "" : "s"})` : "Title",
      `From ${titleSource}. Limit ${DISCORD_LIMITS.titleBytes} bytes / ${DISCORD_LIMITS.titleLines} lines.`,
    );
  } else {
    check("text", "warn", "No title", "Add og:title.");
  }
  if (description) {
    check(
      "text",
      description.trimmed ? "warn" : "pass",
      description.trimmed ? `Description trimmed (${description.bytes} bytes, ${description.lines} lines)` : "Description",
      `From ${descSource}. Limit ${DISCORD_LIMITS.descriptionBytes} bytes / ${DISCORD_LIMITS.descriptionLines} lines. ${isRich ? "Rendered as Discord markdown (og:type rich)." : "Plain text — HTML and markdown appear literally."}`,
    );
  } else {
    check("text", "info", "No description");
  }

  // Site name (first wins), overridden by oEmbed provider_name
  let siteName = firstNonEmpty(tags, ["og:site_name"])?.content.trim();
  if (siteName && siteName.length > DISCORD_LIMITS.siteNameChars) {
    check("text", "fail", `og:site_name is ${siteName.length} characters`, `Over ${DISCORD_LIMITS.siteNameChars} means no preview at all.`);
    blockers.push("og:site_name too long");
  }
  let providerUrl: string | undefined;
  if (oembed?.providerName) {
    siteName = oembed.providerName;
    providerUrl = oembed.providerUrl;
    check("oembed", "info", "oEmbed provider_name overrides og:site_name", providerUrl ? `Linked to ${providerUrl}` : undefined);
  }
  const author = oembed?.authorName ? { name: oembed.authorName, url: oembed.authorUrl } : undefined;
  if (author) check("oembed", "pass", "Author line from oEmbed", "oEmbed is the only way to put an author on a preview.");

  // Layout tags: last one in the document wins
  if (ogType && !(ogType === "rich" || ogType === "video" || ogType.startsWith("video.") || ogType === "instapp:photo")) {
    check("text", "info", `og:type "${ogType}" doesn't change the layout`, "Only rich, video, video.* and instapp:photo do.");
  }
  if (twitterCard && !["player", "summary_large_image", "photo"].includes(twitterCard)) {
    check("text", "info", `twitter:card "${twitterCard}" doesn't change the layout`, "Only player, summary_large_image and photo do.");
  }

  // Accent color (last wins)
  const colorTag = lastNonEmpty(tags, ["theme-color"]);
  let color: string | undefined;
  if (colorTag) {
    const parsed = parseThemeColor(colorTag.content);
    if (parsed) {
      color = parsed;
      check("text", "pass", `Accent color ${colorTag.content.trim()}`);
    } else {
      check("text", "warn", `theme-color "${colorTag.content}" is ignored`, "Discord accepts #RRGGBB or #RRGGBBAA only (no names, rgb() or 3-digit hex).");
    }
  }

  // Publish date (last wins)
  const dateTag = lastNonEmpty(tags, ["og:pubdate", "pubdate", "iso-8601-publish-date"]);
  let timestamp: string | undefined;
  if (dateTag) {
    if (isRfc3339(dateTag.content)) {
      timestamp = new Date(dateTag.content.trim()).toISOString();
      check("text", "pass", `Timestamp from ${dateTag.property}`);
    } else {
      check("text", "warn", `${dateTag.property} "${dateTag.content}" is ignored`, "Use a full RFC 3339 timestamp with a time and an offset, e.g. 2026-09-15T12:00:00Z.");
    }
  } else if (tags.some((t) => t.property === "article:published_time")) {
    check("text", "info", "article:published_time isn't read", "Discord reads og:pubdate, pubdate or iso-8601-publish-date for the timestamp.");
  }

  // ── Images ──
  const PLAIN_IMAGE_KEYS = ["og:image", "twitter:image"];
  const VARIANT_IMAGE_KEYS = ["og:image:url", "og:image:secure_url", "twitter:image:src"];
  const rawImages: PreviewImage[] = [];
  let lastPlain: PreviewImage | null = null;
  let variantDims = false;
  let orphanDims = false;
  const badRelative: string[] = [];
  let lastStartWasVariant = false;

  for (const tag of tags) {
    const key = tag.property;
    const value = tag.content.trim();
    if (PLAIN_IMAGE_KEYS.includes(key) || VARIANT_IMAGE_KEYS.includes(key)) {
      if (!value) continue;
      const url = resolveDiscordUrl(value, pageUrl);
      if (!url) {
        badRelative.push(`${key}: ${value}`);
        continue;
      }
      const image: PreviewImage = { url, key };
      rawImages.push(image);
      if (PLAIN_IMAGE_KEYS.includes(key)) {
        lastPlain = image;
        lastStartWasVariant = false;
      } else {
        lastStartWasVariant = true;
      }
      continue;
    }
    const isDim = ["og:image:width", "og:image:height", "twitter:image:width", "twitter:image:height"].includes(key);
    const isMeta = ["og:image:type", "og:image:alt", "twitter:image:alt"].includes(key);
    if (!isDim && !isMeta) continue;
    if (lastStartWasVariant) variantDims = true;
    // Dimension/type/alt tags attach to the most recent plain og:image / twitter:image
    if (!lastPlain) {
      orphanDims = true;
      continue;
    }
    if (key.endsWith(":width")) lastPlain.width = toDimension(value);
    else if (key.endsWith(":height")) lastPlain.height = toDimension(value);
    else if (key === "og:image:type") lastPlain.type = value;
    else lastPlain.alt = tag.content;
  }

  // The same image declared under several keys is one image
  const byUrl = new Map<string, PreviewImage>();
  for (const img of rawImages) {
    const seen = byUrl.get(img.url);
    if (!seen) byUrl.set(img.url, { ...img });
    else {
      seen.width ??= img.width;
      seen.height ??= img.height;
      seen.type ??= img.type;
      seen.alt ??= img.alt;
    }
  }
  let allImages = [...byUrl.values()];

  // JSON-LD VideoObject (fills gaps only — precedence vs Open Graph is undocumented)
  let ldVideo: Record<string, unknown> | undefined;
  for (const block of result.jsonLd ?? []) {
    let node: unknown;
    try {
      node = JSON.parse(block.raw);
    } catch {
      check("media", "warn", "A JSON-LD block isn't valid JSON", block.truncated ? "It was also cut at the inspector's 32 KB cap." : undefined);
      continue;
    }
    if (isVideoObject(node)) {
      ldVideo ??= node;
    } else if (
      (Array.isArray(node) && node.some(isVideoObject)) ||
      (typeof node === "object" && node !== null && Array.isArray((node as Record<string, unknown>)["@graph"]) &&
        ((node as Record<string, unknown>)["@graph"] as unknown[]).some(isVideoObject))
    ) {
      check("media", "warn", "VideoObject isn't top-level", "Discord reads only a top-level schema.org VideoObject, not one inside an array or @graph.");
    }
  }
  if (ldVideo) {
    check("media", "pass", "JSON-LD VideoObject found", "Discord reads name, description, embedUrl, thumbnailUrl and uploadDate.");
    const thumb = Array.isArray(ldVideo.thumbnailUrl) ? ldVideo.thumbnailUrl[0] : ldVideo.thumbnailUrl;
    const thumbUrl = typeof thumb === "string" ? resolveDiscordUrl(thumb, pageUrl) : null;
    if (!allImages.length && thumbUrl) allImages = [{ url: thumbUrl, key: "JSON-LD thumbnailUrl" }];
    const uploadDate = str(ldVideo.uploadDate);
    if (!timestamp && uploadDate && isRfc3339(uploadDate)) timestamp = new Date(uploadDate).toISOString();
  }

  // oEmbed thumbnail fills the image only when Open Graph gave none, and only with both dimensions
  if (oembed?.thumbnailUrl) {
    if (!oembed.thumbnailWidth || !oembed.thumbnailHeight) {
      check("oembed", "warn", "oEmbed thumbnail ignored", "thumbnail_url needs thumbnail_width and thumbnail_height alongside it.");
    } else if (!allImages.length) {
      const url = resolveDiscordUrl(oembed.thumbnailUrl, pageUrl);
      if (url) allImages = [{ url, key: "oEmbed thumbnail_url", width: oembed.thumbnailWidth, height: oembed.thumbnailHeight }];
    }
  }

  for (const bad of badRelative) {
    check("media", "fail", "Relative image URL not read", `${bad} — relative URLs must start with "/".`);
  }
  if (orphanDims) check("media", "warn", "Image size/type/alt tag before any og:image", "These attach to the most recent og:image or twitter:image above them; this one has none.");
  if (variantDims) {
    check("media", "warn", "Image dimensions follow og:image:url / secure_url / twitter:image:src", "Dimension, type and alt tags only attach to plain og:image and twitter:image; declare the image with og:image.");
  }

  for (const img of allImages) {
    const label = img.url.length > 80 ? `${img.url.slice(0, 77)}…` : img.url;
    if (img.url.length > DISCORD_LIMITS.urlChars) {
      check("media", "fail", "Image URL over 2,048 characters", label);
      blockers.push("a URL over 2,048 characters");
    }
    if (img.alt && img.alt.length > DISCORD_LIMITS.altChars) {
      check("media", "fail", `Image alt text is ${img.alt.length} characters`, `Over ${DISCORD_LIMITS.altChars} means no preview at all.`);
      blockers.push("image alt text too long");
    }
    const ext = urlExtension(img.url);
    if (img.type) {
      if (!IMAGE_MIME_TYPES.includes(img.type.toLowerCase())) {
        check("media", "fail", `og:image:type ${img.type} isn't supported`, `Supported: ${IMAGE_EXTENSIONS.join(", ")}.`);
      }
    } else if (ext && !(IMAGE_EXTENSIONS as readonly string[]).includes(ext)) {
      check("media", "warn", `.${ext} image`, `${label} — Discord supports ${IMAGE_EXTENSIONS.join(", ")}.`);
    }
    if (PLAIN_IMAGE_KEYS.includes(img.key) && (!img.width || !img.height)) {
      check("media", "warn", "Image has no declared width/height", `${label} — without og:image:width and og:image:height Discord fetches the image to measure it, which spends the 10 s budget.`);
    }
  }

  const ogImageUrls = [...new Set(rawImages.filter((i) => i.key === "og:image").map((i) => i.url))];
  const isGallery = ogImageUrls.length >= 2;
  if (isGallery && ogImageUrls.length > DISCORD_LIMITS.imagesPerLink) {
    check("media", "info", `${ogImageUrls.length} og:image tags — only 4 are shown`, "A component embed's Media Gallery can show up to 10.");
  }

  // ── Video ──
  const videoTag = firstNonEmpty(tags, ["og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream"]);
  let video: PreviewVideo | undefined;
  const videoUrlRaw = videoTag?.content.trim() ?? str(ldVideo?.embedUrl);
  if (videoUrlRaw) {
    const url = resolveDiscordUrl(videoUrlRaw, pageUrl);
    if (url) {
      video = {
        url,
        key: videoTag?.property ?? "JSON-LD embedUrl",
        type: firstNonEmpty(tags, ["og:video:type", "twitter:player:stream:content_type"])?.content.trim(),
        width: toDimension(firstNonEmpty(tags, ["og:video:width", "twitter:player:width", "og:width"])?.content),
        height: toDimension(firstNonEmpty(tags, ["og:video:height", "twitter:player:height", "og:height"])?.content),
      };
    } else {
      check("media", "fail", "Relative video URL not read", `${videoUrlRaw} — relative URLs must start with "/".`);
    }
  } else if (tags.some((t) => t.property === "twitter:player")) {
    check("media", "info", "twitter:player is a player page, not a video file", "Discord plays a file from og:video or twitter:player:stream.");
  }

  const wantsVideo =
    ogType === "video" || !!ogType?.startsWith("video.") || twitterCard === "player" ||
    (!ogType && !twitterCard && oembed?.type === "video");
  let videoOk = false;
  /** og:video pointing at an HTML player page (YouTube-style), not a file */
  const playerPage = !!video && (video.type?.toLowerCase() === "text/html" || video.type?.toLowerCase() === "application/x-shockwave-flash");
  if (video && playerPage) {
    check(
      "media",
      "warn",
      "og:video is a player page, not a video file",
      `${video.type} — Discord's documented unfurler plays only .mp4, .webm and .mov files, so this preview has no inline player. A few large sites get Discord-specific embeds this inspector can't reproduce.`,
    );
  } else if (video) {
    const problems: string[] = [];
    if (!wantsVideo) problems.push('set og:type to "video.other" (or video) or twitter:card to "player"');
    if (!video.url.startsWith("https://")) problems.push("serve the file over https");
    if (!allImages.length) problems.push("add og:image — it is required and becomes the preview frame");
    if (video.type && !VIDEO_MIME_TYPES.includes(video.type.toLowerCase())) {
      problems.push(`${video.type} isn't supported (video/mp4, video/webm, video/quicktime)`);
    } else if (!video.type) {
      const ext = urlExtension(video.url);
      if (ext && !(VIDEO_EXTENSIONS as readonly string[]).includes(ext)) problems.push(`.${ext} isn't a supported video format (mp4, webm, mov)`);
    }
    if (video.url.length > DISCORD_LIMITS.urlChars) {
      problems.push("the URL is over 2,048 characters");
      blockers.push("a URL over 2,048 characters");
    }
    videoOk = problems.length === 0;
    check(
      "media",
      videoOk ? "pass" : "fail",
      videoOk ? "Inline video" : "Video won't play inline",
      videoOk ? `${video.url}${video.width && video.height ? "" : " — no declared size, so Discord must be able to measure the file or the video is dropped."}` : `To fix: ${problems.join("; ")}.`,
    );
  } else if (wantsVideo) {
    check("media", "warn", "Video layout requested but no video file", "Point og:video at an .mp4, .webm or .mov file.");
  }

  // Any other URL Discord would use
  for (const [label, url] of [
    ["oEmbed author_url", author?.url],
    ["oEmbed provider_url", providerUrl],
  ] as const) {
    if (url && url.length > DISCORD_LIMITS.urlChars) {
      check("oembed", "fail", `${label} over 2,048 characters`);
      blockers.push("a URL over 2,048 characters");
    }
  }

  // ── Layout (precedence between conflicting layout tags is the inspector's reading) ──
  const isPhoto =
    twitterCard === "photo" || ogType === "instapp:photo" || (!ogType && !twitterCard && oembed?.type === "photo");
  const oembedRich = !ogType && !twitterCard && oembed?.type === "rich";
  let layout: PreviewLayout;
  if (videoOk) layout = "video";
  else if (isPhoto && allImages.length) layout = "photo";
  else if (isGallery) layout = "gallery";
  else if (isRich || oembedRich) layout = "rich";
  else if (twitterCard === "summary_large_image" && allImages.length) layout = "large-image";
  else if (allImages.length) layout = "thumbnail";
  else if (title || description) layout = "text";
  else layout = "none";

  if (blockers.length) layout = "none";

  const images =
    layout === "gallery"
      ? ogImageUrls.slice(0, DISCORD_LIMITS.imagesPerLink).map((u) => byUrl.get(u)!)
      : layout === "none" || layout === "text"
        ? []
        : allImages.slice(0, 1);

  if (layout === "none" && blockers.length) {
    check("text", "fail", "No preview", `Discord shows nothing for this page: ${[...new Set(blockers)].join(", ")}.`);
  } else if (layout === "none") {
    check("text", "fail", "Nothing to preview", "No title, description, image or video was found in the server-rendered HTML.");
  }

  // ── Component embeds ──
  const ogFallback = !!(firstNonEmpty(tags, ["og:title"]) && firstNonEmpty(tags, ["og:description"]) && firstNonEmpty(tags, ["og:image"]));
  const componentEmbeds = (result.componentEmbeds ?? []).map((element) => assessComponentEmbed(element));
  const activeComponentEmbed = componentEmbeds.find((c) => c.usable);

  if (componentEmbeds.length) {
    for (const report of componentEmbeds) {
      const where = report.element.source === "inline" ? "Inline <script>" : `Linked JSON (${report.element.href || "no href"})`;
      if (report.elementErrors.length) {
        check("component", "fail", `${where} can't be used`, report.elementErrors.join(" "));
      }
      const v = report.validation;
      if (v) {
        const errorsCount = v.issues.filter((i) => i.severity === "error").length;
        const warnCount = v.issues.length - errorsCount;
        check(
          "component",
          errorsCount ? "fail" : warnCount ? "warn" : "pass",
          `${where}: ${errorsCount ? `${errorsCount} error${errorsCount === 1 ? "" : "s"}` : "valid"}${warnCount ? `, ${warnCount} warning${warnCount === 1 ? "" : "s"}` : ""}`,
          [
            `${v.bytes.toLocaleString()} / 3,000 bytes · ${v.componentCount} / 40 components · ${v.galleryItemCount} / 10 gallery items.`,
            // Name the first few problems here; the builder lists them all
            ...v.issues.slice(0, 3).map((i) => (i.path ? `${i.path}: ${i.message}` : i.message)),
            v.issues.length > 3 ? `…and ${v.issues.length - 3} more — open it in the builder.` : "",
          ].filter(Boolean).join(" "),
        );
      }
    }
    if (componentEmbeds.length > 1) {
      check("component", "info", `${componentEmbeds.length} component-embed elements`, "Discord doesn't document which one wins; the preview uses the first usable one.");
    }
    check(
      "component",
      ogFallback ? "pass" : "warn",
      ogFallback ? "Open Graph fallback present" : "Open Graph fallback incomplete",
      "When a component embed can't be shown, Discord falls back to og:title, og:description and og:image.",
    );
  }

  return {
    preview: {
      layout,
      url: pageUrl,
      title: layout === "none" ? undefined : title,
      titleSource,
      description: layout === "none" || layout === "photo" || layout === "video" ? undefined : description,
      descriptionSource: descSource,
      markdown: layout === "rich",
      siteName: layout === "none" ? undefined : siteName,
      providerUrl,
      author,
      images,
      allImages,
      video: layout === "video" ? video : undefined,
      color,
      timestamp,
    },
    componentEmbeds,
    activeComponentEmbed,
    oembed,
    checks,
    cacheBustUrl: cacheBustUrl(pageUrl, now),
  };
}

/** Element-level rules for one component-embed candidate, then payload validation */
export function assessComponentEmbed(element: ComponentEmbedSource): ComponentEmbedReport {
  const elementErrors: string[] = [];
  const type = element.type?.trim().toLowerCase() ?? "";
  if (!(COMPONENT_EMBED_MIME_TYPES as readonly string[]).includes(type)) {
    elementErrors.push(`type="${element.type ?? ""}" — use application/json or application/vnd.discord.component-embed+json.`);
  }
  if (element.source === "inline" && element.inHead === false) {
    elementErrors.push("The script must be inside <head>.");
  }
  if (element.source === "linked") {
    if (!element.href) elementErrors.push("The link has no href.");
    else if (element.hostAllowed === false) {
      elementErrors.push("href must be an absolute URL on this page's host, a subdomain of it, or its parent domain.");
    }
    if (element.error) elementErrors.push(`Fetching it failed: ${element.error}.`);
  }
  if (element.truncated) {
    elementErrors.push(`Payload is over ${element.bytes.toLocaleString()} bytes (limit 3,000).`);
  }

  const validation = element.raw !== undefined && !element.truncated ? validateComponentEmbed(element.raw) : undefined;
  return {
    element,
    validation,
    elementErrors,
    usable: elementErrors.length === 0 && !!validation?.ok,
  };
}
