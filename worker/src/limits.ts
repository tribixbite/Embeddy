/**
 * Deadlines, size caps and body helpers shared by the Worker routes.
 *
 * Kept out of index.ts because a Workers module's named exports are treated
 * as entrypoints — only the Hono app is exported from there.
 */

/**
 * Upstream deadlines (M6). Without them a slow-drip upstream holds the
 * request open indefinitely. The inspect deadline spans every redirect hop.
 */
export const INSPECT_TIMEOUT_MS = 10_000;
export const UPLOAD_TIMEOUT_MS = 120_000;

/**
 * Response-size caps for /api/inspect (L1): a 2 MB page of meta tags would
 * otherwise come back as a ~2 MB JSON array of unbounded strings.
 */
export const MAX_TAGS = 200;
export const MAX_TAG_NAME_CHARS = 256;
/**
 * Kept just above Discord's largest per-field limit (4,096-char image alt
 * text; URLs cap at 2,048) so the client can still tell when a value is OVER
 * a limit — a cap at or below it would make every long value look compliant.
 */
export const MAX_TAG_CONTENT_CHARS = 4_200;

/**
 * Discord-source caps. A component embed must be ≤ 3,000 bytes, so storing up
 * to 16 KB is enough to show (and measure) an oversized payload. oEmbed and
 * JSON-LD documents are only read for a handful of short fields.
 */
export const MAX_EMBED_JSON_BYTES = 16 * 1024;
export const MAX_OEMBED_BYTES = 32 * 1024;
export const MAX_JSONLD_BLOCKS = 5;
export const MAX_JSONLD_BYTES = 32 * 1024;
/** Deadline for each secondary fetch (oEmbed / linked component embed). */
export const SUBFETCH_TIMEOUT_MS = 5_000;
/** Upstream error text echoed back to the client is cut to this length (L1). */
export const MAX_ERROR_TEXT_CHARS = 300;

/** Cut `text` to `max` characters, marking the cut with an ellipsis. */
export function truncate(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max - 1)}…`;
}

/** True for the DOMException that AbortSignal.timeout()/abort() rejects with. */
export function isTimeoutError(err: unknown): boolean {
  return (
    typeof err === "object" &&
    err !== null &&
    ((err as { name?: unknown }).name === "TimeoutError" || (err as { name?: unknown }).name === "AbortError")
  );
}

/**
 * Read a response body until `maxBytes`, then cancel the stream.
 * Nothing is retained — this exists purely to drive HTMLRewriter's handlers.
 * Exported for testing.
 * @returns Bytes read before stopping
 */
export async function drainCapped(response: Response, maxBytes: number): Promise<number> {
  const body = response.body;
  if (!body) return 0;

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
  return read;
}

