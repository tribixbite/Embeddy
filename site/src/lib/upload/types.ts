/**
 * Types for the Upload tool — anonymous file hosting.
 */

/** Supported upload hosts */
export type UploadHost = "0x0.st" | "catbox.moe";

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
