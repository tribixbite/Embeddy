package app.embeddy.ui.components

/**
 * Pure helpers behind ConversionProgressCard, extracted so plain-JVM tests can
 * pin the indeterminate-bar fallback (the Compose card itself is untestable
 * off-device).
 */

/** How long progress may sit at zero before the bar switches to indeterminate. */
const val INDETERMINATE_AFTER_MS = 1000L

/**
 * Elapsed time to display: the greater of what the engine reported and what a
 * UI-side wall clock measured since the card appeared.
 *
 * Engine stats may never arrive (`elapsedMs` then stays 0 forever) and preview
 * runs hardcode 0 — driving elapsed purely from engine events froze the label
 * at "0s" and made the indeterminate fallback unreachable.
 */
fun effectiveElapsedMs(reportedElapsedMs: Long, wallClockElapsedMs: Long): Long =
    maxOf(reportedElapsedMs, wallClockElapsedMs).coerceAtLeast(0L)

/**
 * True when the bar should render indeterminate: no progress data has arrived
 * after a grace period of [INDETERMINATE_AFTER_MS].
 */
fun isProgressIndeterminate(progressFraction: Float, elapsedMs: Long): Boolean =
    progressFraction <= 0f && elapsedMs > INDETERMINATE_AFTER_MS

/** Progress fraction below which no remaining-time estimate is shown. */
const val ESTIMATE_MIN_PROGRESS = 0.05f

/** Elapsed time below which no remaining-time estimate is shown. */
const val ESTIMATE_MIN_ELAPSED_MS = 2_000L

/** Floor for the fraction inside the projection, bounding the extrapolation. */
private const val ESTIMATE_FRACTION_FLOOR = 0.02f

/**
 * Linear projection of the time left in the current encode attempt:
 * `elapsed * (1 - f) / f`, where `f` is the progress fraction.
 *
 * Returns null until both [ESTIMATE_MIN_PROGRESS] and [ESTIMATE_MIN_ELAPSED_MS]
 * are reached — extrapolating from the first moments of an encode produces
 * wild numbers that only churn. Callers should pass the same effective elapsed
 * the card displays (max of engine-reported and wall clock), re-based per
 * attempt: the adaptive target-size loop restarts encoding from 0% at each
 * lower quality, so inheriting a previous attempt's elapsed would inflate the
 * projection.
 */
fun estimateRemainingMs(progress: Float, elapsedMs: Long): Long? {
    if (progress < ESTIMATE_MIN_PROGRESS || elapsedMs < ESTIMATE_MIN_ELAPSED_MS) return null
    val fraction = progress.coerceIn(ESTIMATE_FRACTION_FLOOR, 1f).toDouble()
    return (elapsedMs * (1.0 - fraction) / fraction).toLong().coerceAtLeast(0L)
}

/**
 * Compact duration for the ETA label: whole seconds under a minute ("42s"),
 * m:ss at or above it ("1:30"). Partial seconds round UP so the countdown
 * never promises less time than remains.
 */
fun formatRemainingShort(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) + 999) / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "%d:%02d".format(minutes, seconds) else "${seconds}s"
}
