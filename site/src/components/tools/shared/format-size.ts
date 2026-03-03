/**
 * Format byte count as human-readable file size.
 * Uses binary units (KiB, MiB) for precision, but labels as KB/MB for familiarity.
 */
export function formatFileSize(bytes: number): string {
  if (bytes === 0) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  const k = 1024;
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  const size = bytes / Math.pow(k, i);
  return `${size.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
}

/**
 * Calculate and format percentage savings between original and compressed sizes.
 * Returns a string like "-42.3%" or "+5.1%" (positive = larger).
 */
export function formatSavings(original: number, compressed: number): string {
  if (original === 0) return "0%";
  const pct = ((compressed - original) / original) * 100;
  const sign = pct <= 0 ? "" : "+";
  return `${sign}${pct.toFixed(1)}%`;
}
