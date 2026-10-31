/**
 * Client-side wrapper for the Cloudflare Worker inspect API.
 * Fetches OG/Twitter metadata for a given URL via the proxy.
 */

import type { MetadataResult } from "./types";
import { API_BASE, apiErrorMessage } from "../api";

/**
 * Fetch metadata for a URL via the CF Worker proxy.
 * The worker fetches the page, parses meta tags via HTMLRewriter,
 * and returns structured JSON.
 */
export async function fetchMetadata(url: string): Promise<MetadataResult> {
  const encoded = encodeURIComponent(url);
  const response = await fetch(`${API_BASE}/api/inspect?url=${encoded}`);

  if (!response.ok) {
    throw new Error(apiErrorMessage(await response.text(), response.status));
  }

  return response.json();
}
