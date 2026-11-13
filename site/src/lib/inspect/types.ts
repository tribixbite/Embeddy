/**
 * Types for the Inspect tool — link-preview metadata inspection.
 * Mirrors the /api/inspect response shape in worker/src/index.ts.
 */

/** A single metadata tag extracted from a page */
export interface MetaTag {
  /**
   * Tag key as Discord reads it: the `name` attribute when present, otherwise
   * `property` (e.g. "og:title", "twitter:card"). Case-sensitive.
   */
  property: string;
  /** Tag value: `content` when present, otherwise `value` */
  content: string;
  /** Which attribute supplied the key; absent for link-derived entries (favicon) */
  attr?: "name" | "property";
}

/** Crawler identity the Worker fetched the page as */
export type CrawlerIdentity = "discord" | "embeddy";

/** Facts about the page fetch that decide whether Discord previews it at all */
export interface FetchInfo {
  requestedUrl: string;
  /** Every URL that answered with a redirect, in order */
  redirects: string[];
  userAgent: CrawlerIdentity;
  contentType: string;
  contentDisposition?: string;
  contentLength?: number;
  /** HTML bytes the inspector read */
  bytesRead: number;
  /** True when the inspector stopped reading at its own cap */
  readCapped: boolean;
  /** Page fetch time in ms (headers + body) */
  ms: number;
}

/** A component-embed payload found on the page */
export interface ComponentEmbedSource {
  source: "inline" | "linked";
  /** The element's `type` attribute as written */
  type: string | null;
  /** Inline only: whether the script sat inside <head> */
  inHead?: boolean;
  /** Linked only: href as written */
  href?: string;
  /** Linked only: whether href passes Discord's same-site rule */
  hostAllowed?: boolean;
  /** Raw payload text (cut at the Worker's cap) */
  raw?: string;
  /** Payload size in bytes */
  bytes: number;
  truncated: boolean;
  fetchStatus?: number;
  fetchMs?: number;
  error?: string;
}

/** The oEmbed document Discord would read */
export interface OEmbedSource {
  href: string;
  /** Number of JSON oEmbed links declared; only the first is used */
  count: number;
  status: number;
  ms: number;
  raw?: string;
  bytes: number;
  truncated: boolean;
  error?: string;
}

/** Raw text of one JSON-LD block */
export interface JsonLdBlock {
  raw: string;
  bytes: number;
  truncated: boolean;
}

/** Full metadata result from inspecting a URL */
export interface MetadataResult {
  /** The resolved URL that was fetched (after redirects) */
  url: string;
  /** HTTP status code */
  status: number;
  /** Page title from <title> tag */
  title: string;
  /** All extracted meta tags, in document order */
  tags: MetaTag[];
  /** Page fetch facts (absent from older Worker deployments) */
  fetch?: FetchInfo;
  /** Set when the URL points straight at an image or video file */
  direct?: { kind: "image" | "video" };
  oembed?: OEmbedSource;
  componentEmbeds?: ComponentEmbedSource[];
  jsonLd?: JsonLdBlock[];
  /** Resolved OG image URL (absolute) */
  ogImage?: string;
  /** OG title */
  ogTitle?: string;
  /** OG description */
  ogDescription?: string;
  /** OG site name */
  ogSiteName?: string;
  /** Twitter card type */
  twitterCard?: string;
  /** Theme color */
  themeColor?: string;
  /** Favicon URL */
  favicon?: string;
}

/** EXIF data from a local image file (via exifr) */
export interface ExifData {
  [key: string]: unknown;
}
