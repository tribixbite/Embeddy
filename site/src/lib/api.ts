/**
 * Shared client config for the Cloudflare Worker API (D3).
 *
 * Both the inspect and upload clients used to hardcode the production URL
 * and duplicate the `{ error }` payload parsing. The base URL can be pointed
 * at a local `wrangler dev` with PUBLIC_API_BASE (e.g. in site/.env).
 */

/** Production API origin */
export const DEFAULT_API_BASE = "https://api.embeddy.link";

/**
 * Normalise a configured API base: falls back to production when unset or
 * blank, and drops trailing slashes so `${base}/api/...` never doubles up.
 */
export function resolveApiBase(configured: string | undefined): string {
  const value = configured?.trim();
  return (value ? value : DEFAULT_API_BASE).replace(/\/+$/, "");
}

/** The API origin used by every client */
export const API_BASE = resolveApiBase(import.meta.env?.PUBLIC_API_BASE as string | undefined);

/** The `error` string of a Worker `{"error": "..."}` body, or null if absent/not JSON. */
export function parseApiError(body: string): string | null {
  try {
    const payload = JSON.parse(body) as { error?: unknown } | null;
    if (payload && typeof payload.error === "string" && payload.error) return payload.error;
  } catch {
    // Not JSON
  }
  return null;
}

/**
 * Turn an error response body into a user-facing message. The Worker reports
 * failures as `{"error": "..."}` — surface that message, not the raw JSON;
 * a non-JSON body (plain-text gateway error) is shown as-is, and an empty one
 * becomes `HTTP <status>`.
 */
export function apiErrorMessage(body: string, status: number): string {
  return parseApiError(body) ?? (body.trim() || `HTTP ${status}`);
}
