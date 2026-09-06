/**
 * Client-side wrapper for the Cloudflare Worker inspect API.
 * Fetches OG/Twitter metadata for a given URL via the proxy.
 */

import type { MetadataResult } from "./types";

/** Base URL for the Cloudflare Worker API */
const API_BASE = "https://api.embeddy.link";

/**
 * Fetch metadata for a URL via the CF Worker proxy.
 * The worker fetches the page, parses meta tags via HTMLRewriter,
 * and returns structured JSON.
 */
export async function fetchMetadata(url: string): Promise<MetadataResult> {
  const encoded = encodeURIComponent(url);
  const response = await fetch(`${API_BASE}/api/inspect?url=${encoded}`);

  if (!response.ok) {
    const text = await response.text();
    // The worker reports failures as {"error":"..."} — surface the message,
    // not the raw JSON blob (mirrors lib/upload/proxy.ts).
    let message = text || `HTTP ${response.status}`;
    try {
      const payload = JSON.parse(text) as { error?: string };
      if (payload?.error) message = payload.error;
    } catch {
      // Non-JSON body (e.g. a plain-text gateway error) — keep the raw text
    }
    throw new Error(message);
  }

  return response.json();
}
