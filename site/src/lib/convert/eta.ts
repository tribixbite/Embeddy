/**
 * Time-remaining estimation for long-running convert phases.
 *
 * Pure linear extrapolation: if `fraction` of the work took `elapsedMs`,
 * the rest takes `elapsedMs * (1 - fraction) / fraction`. Two gates keep
 * early estimates (which extrapolate a tiny noisy sample) from flashing
 * wild numbers at the user: a minimum fraction AND a minimum elapsed time.
 */

/** Fractions below this are clamped up before dividing, bounding the multiplier */
export const FRACTION_FLOOR = 0.02;
/** No estimate until at least this fraction of the work is done */
export const ETA_MIN_FRACTION = 0.05;
/** No estimate until at least this much wall time has passed */
export const ETA_MIN_ELAPSED_MS = 2000;

/**
 * Estimate remaining time from progress fraction (0-1) and elapsed wall time.
 * Returns null when there isn't enough signal yet (or on garbage input) —
 * callers should show nothing rather than a bogus number.
 */
export function estimateRemainingMs(fraction: number, elapsedMs: number): number | null {
  if (!Number.isFinite(fraction) || !Number.isFinite(elapsedMs)) return null;
  if (fraction < ETA_MIN_FRACTION || elapsedMs < ETA_MIN_ELAPSED_MS) return null;
  if (fraction >= 1) return 0;
  const f = Math.max(FRACTION_FLOOR, fraction);
  return (elapsedMs * (1 - f)) / f;
}

/**
 * Format a remaining-time estimate for display: "~12s left", "~1m 05s left".
 * Positive sub-second values round up to 1s so the label never claims "0s"
 * while work is still running.
 */
export function formatEta(ms: number): string {
  if (ms <= 0) return "~0s left";
  const totalSeconds = Math.max(1, Math.round(ms / 1000));
  if (totalSeconds < 60) return `~${totalSeconds}s left`;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `~${minutes}m ${String(seconds).padStart(2, "0")}s left`;
}
