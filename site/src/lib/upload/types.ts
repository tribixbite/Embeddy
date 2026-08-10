/**
 * Types for the Upload tool — anonymous file hosting.
 */

/** Supported upload hosts */
export type UploadHost = "0x0.st" | "catbox.moe";

/**
 * Largest body the Cloudflare Worker relay will accept.
 * Must stay in sync with MAX_UPLOAD_BYTES in worker/src/index.ts.
 */
export const RELAY_MAX_BYTES = 95 * 1024 * 1024;

/** Each host's published size limit, clamped by what the relay can pass through. */
export const HOST_LIMITS: Record<UploadHost, number> = {
  "0x0.st": Math.min(512 * 1024 * 1024, RELAY_MAX_BYTES),
  "catbox.moe": Math.min(200 * 1024 * 1024, RELAY_MAX_BYTES),
};

/** Result from a successful upload */
export interface UploadResult {
  /** The file URL returned by the host */
  url: string;
  /** Which host was used */
  host: UploadHost;
  /** Upload duration in ms */
  durationMs: number;
}

/** Upload progress info */
export interface UploadProgress {
  /** Bytes sent so far */
  loaded: number;
  /** Total bytes to send */
  total: number;
  /** Progress 0-100 */
  percent: number;
}
