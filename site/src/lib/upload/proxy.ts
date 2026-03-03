/**
 * Upload a file to the CF Worker relay with XHR for progress tracking.
 * The worker forwards the multipart POST to the selected host.
 */

import type { UploadHost, UploadResult, UploadProgress } from "./types";

/** Base URL for the Cloudflare Worker API */
const API_BASE = "https://api.embeddy.link";

/**
 * Upload a file via the CF Worker relay.
 * Uses XMLHttpRequest for upload progress events.
 */
export function uploadFile(
  file: File,
  host: UploadHost,
  onProgress?: (progress: UploadProgress) => void,
): Promise<UploadResult> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
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
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          const data = JSON.parse(xhr.responseText);
          resolve({
            url: data.url,
            host,
            durationMs: Date.now() - startTime,
          });
        } catch {
          reject(new Error("Invalid response from server"));
        }
      } else {
        reject(new Error(xhr.responseText || `Upload failed (HTTP ${xhr.status})`));
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
}
