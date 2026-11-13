/**
 * Client-side wrapper for the Cloudflare Worker inspect API.
 * Fetches link-preview metadata for a given URL via the proxy.
 */

import type { CrawlerIdentity, MetadataResult } from "./types";
import { API_BASE, apiErrorMessage } from "../api";

/** Build the inspect endpoint URL for a page and crawler identity */
export function inspectEndpoint(url: string, crawler: CrawlerIdentity = "discord"): string {
  const params = new URLSearchParams({ url, ua: crawler });
  return `${API_BASE}/api/inspect?${params.toString()}`;
}

/**
 * Fetch metadata for a URL via the CF Worker proxy.
 * The worker fetches the page (as Discordbot by default), parses meta tags,
 * oEmbed, component-embed payloads and JSON-LD via HTMLRewriter, and returns
 * structured JSON.
 * @param crawler Which user agent the Worker presents to the site
 */
export async function fetchMetadata(url: string, crawler: CrawlerIdentity = "discord"): Promise<MetadataResult> {
  const response = await fetch(inspectEndpoint(url, crawler));

  if (!response.ok) {
    throw new Error(apiErrorMessage(await response.text(), response.status));
  }

  return response.json();
}
