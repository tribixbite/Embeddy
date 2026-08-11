/**
 * Upload a file to the CF Worker relay with XHR for progress tracking.
 * The worker forwards the multipart POST to the selected host.
 *
 * Neither 0x0.st nor catbox.moe supports a resumable protocol (no tus, no
 * Range PUT), so a interrupted transfer genuinely has to start over. What we
 * can do — and what the Android UploadEngine already does — is retry transient
 * failures automatically with backoff instead of surfacing them to the user.
 */

import type { UploadHost, UploadResult, UploadProgress } from "./types";

/** Base URL for the Cloudflare Worker API */
const API_BASE = "https://api.embeddy.link";

/** Attempts per upload, including the first. */
const MAX_ATTEMPTS = 3;

/** Linear backoff base — waits 1.5s, then 3s. */
const RETRY_DELAY_MS = 1500;

/** Handle returned alongside the upload promise so the caller can cancel. */
export interface UploadHandle {
  /** Resolves with the hosted URL, or rejects on error/cancellation. */
  result: Promise<UploadResult>;
  /** Abort the in-flight request. */
  cancel: () => void;
}

/** Raised for failures that retrying cannot fix (4xx, bad payload, cancelled). */
class PermanentUploadError extends Error {}

/** Raised when the user aborts — never retried, and reported verbatim. */
class CancelledError extends Error {}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** Perform one upload attempt. Resolves on 2xx, rejects with a typed error. */
function attemptUpload(
  file: File,
  host: UploadHost,
  startTime: number,
  onProgress: ((progress: UploadProgress) => void) | undefined,
  registerXhr: (xhr: XMLHttpRequest) => void,
): Promise<UploadResult> {
  return new Promise<UploadResult>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    registerXhr(xhr);

    const formData = new FormData();
    formData.append("file", file);
    formData.append("host", host);

    xhr.open("POST", `${API_BASE}/api/upload`);

    // Track upload progress
    xhr.upload.addEventListener("progress", (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress({
          loaded: e.loaded,
          total: e.total,
          percent: Math.round((e.loaded / e.total) * 100),
        });
      }
    });

    xhr.addEventListener("load", () => {
      let payload: { url?: string; error?: string } | null = null;
      try {
        payload = JSON.parse(xhr.responseText);
      } catch {
        payload = null;
      }

      if (xhr.status >= 200 && xhr.status < 300) {
        if (!payload?.url) {
          reject(new PermanentUploadError("Invalid response from server"));
          return;
        }
        resolve({
          url: payload.url,
          host,
          durationMs: Date.now() - startTime,
        });
        return;
      }

      // The relay returns { error } — surface that rather than raw JSON
      const message = payload?.error || `Upload failed (HTTP ${xhr.status})`;
      // 4xx means the request itself is wrong (too large, bad host); retrying
      // would just burn the user's bandwidth. 5xx and 0 are worth another go.
      if (xhr.status >= 400 && xhr.status < 500) {
        reject(new PermanentUploadError(message));
      } else {
        reject(new Error(message));
      }
    });

    xhr.addEventListener("error", () => {
      reject(new Error("Network error during upload"));
    });

    xhr.addEventListener("abort", () => {
      reject(new CancelledError("Upload cancelled"));
    });

    xhr.send(formData);
  });
}

/**
 * Upload a file via the CF Worker relay.
 * Uses XMLHttpRequest for upload progress events, retrying transient failures.
 */
export function uploadFile(
  file: File,
  host: UploadHost,
  onProgress?: (progress: UploadProgress) => void,
  onRetry?: (attempt: number, total: number) => void,
): UploadHandle {
  const startTime = Date.now();
  let activeXhr: XMLHttpRequest | null = null;
  let cancelled = false;

  const result = (async () => {
    let lastError: Error = new Error("Upload failed");

    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      if (cancelled) throw new CancelledError("Upload cancelled");

      try {
        return await attemptUpload(file, host, startTime, onProgress, (xhr) => {
          activeXhr = xhr;
          // cancel() can land before the request is created — honour it here
          // rather than letting the attempt run to completion.
          if (cancelled) xhr.abort();
        });
      } catch (err) {
        // Once cancelled, report that — not whatever error the doomed request
        // happened to produce first.
        if (cancelled || err instanceof CancelledError) {
          throw new CancelledError("Upload cancelled");
        }
        if (err instanceof PermanentUploadError) throw err;

        lastError = err instanceof Error ? err : new Error(String(err));
        if (attempt < MAX_ATTEMPTS) {
          onRetry?.(attempt + 1, MAX_ATTEMPTS);
          await sleep(RETRY_DELAY_MS * attempt); // linear backoff
        }
      }
    }

    throw new Error(
      `${lastError.message} (after ${MAX_ATTEMPTS} attempts)`,
    );
  })();

  return {
    result,
    cancel: () => {
      cancelled = true;
      activeXhr?.abort();
    },
  };
}
