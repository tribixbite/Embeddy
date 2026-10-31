/**
 * Byte-size formatting shared by every tool.
 *
 * One definition of "MB" across the site (D5): binary units (1 MB = 1024²
 * bytes), matching the host/relay limits in lib/upload/types.ts and the
 * Convert target-size input, which is entered in these same MB.
 */

/** Bytes per KB/MB/GB/TB step */
export const BYTES_PER_UNIT = 1024;
/** Bytes in one "MB" as displayed and as entered in size inputs */
export const BYTES_PER_MB = BYTES_PER_UNIT * BYTES_PER_UNIT;

const UNITS = ["B", "KB", "MB", "GB", "TB"] as const;

/**
 * Format a byte count as a human-readable size, e.g. `1.5 MB`.
 * Negative values keep their sign; non-finite input renders as `—`.
 */
export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes)) return "—";
  if (bytes === 0) return "0 B";
  const sign = bytes < 0 ? "-" : "";
  const abs = Math.abs(bytes);
  const i = Math.min(UNITS.length - 1, Math.max(0, Math.floor(Math.log(abs) / Math.log(BYTES_PER_UNIT))));
  const size = abs / BYTES_PER_UNIT ** i;
  return `${sign}${size.toFixed(i === 0 ? 0 : 1)} ${UNITS[i]}`;
}

/**
 * Calculate and format percentage change between original and compressed sizes.
 * Returns a string like "-42.3%" or "+5.1%" (positive = larger).
 */
export function formatSavings(original: number, compressed: number): string {
  if (original === 0) return "0%";
  const pct = ((compressed - original) / original) * 100;
  const sign = pct <= 0 ? "" : "+";
  return `${sign}${pct.toFixed(1)}%`;
}
