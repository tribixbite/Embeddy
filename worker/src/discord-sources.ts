/**
 * Discord-specific fetch helpers for /api/inspect.
 *
 * Discord's unfurler reads more than <meta> tags: an oEmbed JSON document
 * discovered through <link type="application/json+oembed">, and a component
 * embed payload either inline in <script id="discord:component-embed"> or
 * linked from <link rel="discord:component-embed">. These helpers fetch those
 * secondary documents the way Discord does (its user agents, same-site rule
 * for linked JSON) without trusting them: every fetch goes through safeFetch
 * and every body is read with a byte cap.
 *
 * Reference: https://docs.discord.com/developers/link-previews/overview and
 * https://docs.discord.com/developers/link-previews/component-embeds
 */

import { BlockedRequestError, safeFetch } from "./ssrf";
import { MAX_ERROR_TEXT_CHARS, isTimeoutError, truncate } from "./limits";

/** User agent Discord's crawler sends for the shared page itself. */
export const DISCORD_PAGE_UA = "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)";

/** User agent Discord sends for linked JSON (component-embed and oEmbed documents). */
export const DISCORD_JSON_UA = "Discordbot/2.0";

/** Our own, honest user agent — selectable for sites that vary by crawler. */
export const EMBEDDY_UA = "Embeddy/1.0 (metadata inspector; +https://embeddy.link)";

/** Which crawler identity the page fetch uses. */
export type CrawlerIdentity = "discord" | "embeddy";

/** Map the `ua` query parameter to a crawler identity (defaults to Discord). */
export function parseCrawlerIdentity(raw: string | undefined): CrawlerIdentity {
  return raw === "embeddy" ? "embeddy" : "discord";
}

/** The page-fetch user agent for a crawler identity. */
export function pageUserAgent(identity: CrawlerIdentity): string {
  return identity === "embeddy" ? EMBEDDY_UA : DISCORD_PAGE_UA;
}

/**
 * Discord's same-site rule for a linked component embed: the href must be an
 * absolute URL on the page's host, a subdomain of it, or its parent domain
 * ("a page on example.com can link to JSON on cdn.example.com, or the other
 * way around"). A sibling subdomain (www → cdn) is neither, so it fails.
 */
export function isLinkedEmbedHostAllowed(href: string, pageUrl: string): boolean {
  let target: URL;
  let page: URL;
  try {
    // No base: a relative href is not absolute and must fail
    target = new URL(href);
    page = new URL(pageUrl);
  } catch {
    return false;
  }
  if (target.protocol !== "https:" && target.protocol !== "http:") return false;
  const a = target.hostname.toLowerCase();
  const b = page.hostname.toLowerCase();
  return a === b || a.endsWith(`.${b}`) || b.endsWith(`.${a}`);
}

/** Outcome of fetching a secondary JSON document. Never throws. */
export interface CappedTextFetch {
  /** URL actually answered (after redirects) */
  url: string;
  /** HTTP status, 0 when no response was received */
  status: number;
  /** Response Content-Type, if any */
  contentType?: string;
  /** Body text, decoded as UTF-8, cut at the byte cap */
  text?: string;
  /** Bytes received (stops counting one past the cap) */
  bytes: number;
  /** True when the body was longer than the cap */
  truncated: boolean;
  /** Wall-clock time for the fetch, including redirects */
  ms: number;
  /** Why the document could not be read */
  error?: string;
}

/**
 * Read at most `maxBytes` of a body as UTF-8 text, then cancel the stream.
 * Reads one byte past the cap so an over-long body is reported as truncated
 * rather than silently matching the cap exactly.
 */
export async function readTextCapped(
  response: Response,
  maxBytes: number,
): Promise<{ text: string; bytes: number; truncated: boolean }> {
  const body = response.body;
  if (!body) return { text: "", bytes: 0, truncated: false };

  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let bytes = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done || !value) break;
      chunks.push(value);
      bytes += value.byteLength;
      if (bytes > maxBytes) break;
    }
  } finally {
    await reader.cancel().catch(() => {});
  }

  const joined = new Uint8Array(Math.min(bytes, maxBytes));
  let offset = 0;
  for (const chunk of chunks) {
    const take = Math.min(chunk.byteLength, joined.byteLength - offset);
    if (take <= 0) break;
    joined.set(chunk.subarray(0, take), offset);
    offset += take;
  }
  // Non-fatal by default: a cut in the middle of a code point becomes U+FFFD
  const text = new TextDecoder("utf-8").decode(joined);
  return { text, bytes, truncated: bytes > maxBytes };
}

/**
 * Fetch a secondary document (oEmbed / linked component embed) through the
 * SSRF-guarded fetcher with Discord's JSON user agent and a deadline.
 * Every failure is reported in the result instead of thrown, because Discord
 * ignores errors in these documents and so must the overall inspection.
 */
export async function fetchTextCapped(
  url: string,
  maxBytes: number,
  timeoutMs: number,
): Promise<CappedTextFetch> {
  const started = Date.now();
  try {
    const { response, finalUrl } = await safeFetch(url, {
      headers: { "User-Agent": DISCORD_JSON_UA, Accept: "application/json, */*;q=0.5" },
      signal: AbortSignal.timeout(timeoutMs),
    });
    const contentType = response.headers.get("content-type") ?? undefined;
    if (!response.ok) {
      await response.body?.cancel().catch(() => {});
      return {
        url: finalUrl,
        status: response.status,
        contentType,
        bytes: 0,
        truncated: false,
        ms: Date.now() - started,
        error: `HTTP ${response.status}`,
      };
    }
    const { text, bytes, truncated } = await readTextCapped(response, maxBytes);
    return { url: finalUrl, status: response.status, contentType, text, bytes, truncated, ms: Date.now() - started };
  } catch (err) {
    let error: string;
    if (err instanceof BlockedRequestError) error = err.message;
    else if (isTimeoutError(err)) error = `No response within ${timeoutMs / 1000}s`;
    else error = truncate(err instanceof Error ? err.message : "Unknown error", MAX_ERROR_TEXT_CHARS);
    return { url, status: 0, bytes: 0, truncated: false, ms: Date.now() - started, error };
  }
}
