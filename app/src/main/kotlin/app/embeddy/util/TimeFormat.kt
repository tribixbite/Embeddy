package app.embeddy.util

/**
 * Compact clip timestamp for trim labels: `"7.4s"` under a minute, `"1:05.3"`
 * above it. Tenths are truncated (not rounded) so a label never claims a
 * position the playhead hasn't reached.
 */
fun formatClipTime(ms: Long): String {
    val clamped = ms.coerceAtLeast(0)
    val totalSec = clamped / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val tenths = (clamped % 1000) / 100
    return if (min > 0) "$min:${sec.toString().padStart(2, '0')}.$tenths" else "$sec.${tenths}s"
}

/** Whole-second duration for file info rows: `"2m 05s"`, or `"42s"` under a minute. */
fun formatMediaDuration(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0) / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min > 0) "${min}m ${sec.toString().padStart(2, '0')}s" else "${sec}s"
}
