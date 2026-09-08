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
