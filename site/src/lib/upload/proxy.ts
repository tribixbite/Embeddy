/**
 * Upload a file to the CF Worker relay with XHR for progress tracking.
 * The worker forwards the multipart POST to the selected host.
 */

import type { UploadHost, UploadResult, UploadProgress } from "./types";

/** Base URL for the Cloudflare Worker API */
const API_BASE = "https://api.embeddy.link";

/** Handle returned alongside the upload promise so the caller can cancel. */
export interface UploadHandle {
  /** Resolves with the hosted URL, or rejects on error/cancellation. */
  result: Promise<UploadResult>;
  /** Abort the in-flight request. */
  cancel: () => void;
}

/**
 * Upload a file via the CF Worker relay.
 * Uses XMLHttpRequest for upload progress events.
 */
export function uploadFile(
  file: File,
  host: UploadHost,
  onProgress?: (progress: UploadProgress) => void,
): UploadHandle {
  const xhr = new XMLHttpRequest();

  const result = new Promise<UploadResult>((resolve, reject) => {
    const startTime = Date.now();

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
          reject(new Error("Invalid response from server"));
          return;
        }
        resolve({
          url: payload.url,
          host,
          durationMs: Date.now() - startTime,
        });
      } else {
        // The relay returns { error } — surface that rather than raw JSON
        reject(
          new Error(payload?.error || `Upload failed (HTTP ${xhr.status})`),
        );
      }
    });

    xhr.addEventListener("error", () => {
      reject(new Error("Network error during upload"));
    });

    xhr.addEventListener("abort", () => {
      reject(new Error("Upload cancelled"));
    });

    xhr.send(formData);
  });

  return { result, cancel: () => xhr.abort() };
}
