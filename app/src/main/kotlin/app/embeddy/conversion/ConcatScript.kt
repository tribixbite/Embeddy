package app.embeddy.conversion

import java.util.Locale

/**
 * Builds FFmpeg concat-demuxer scripts for pre-decoded frame sequences
 * (animated WebP frames, image sequences).
 *
 * A concat script is used rather than `-framerate` + image2 so every frame
 * keeps its own duration. Pure, so the exact script text is unit-tested.
 */
internal object ConcatScript {

    /** WebP and image sequences allow 0 ms delays; FFmpeg needs a real duration. */
    const val MIN_FRAME_DURATION_MS = 20

    /** One frame file (a bare name relative to the script's directory) and its duration. */
    data class Entry(val fileName: String, val durationMs: Int)

    /**
     * Script text for [entries], in order.
     *
     * Durations below [MIN_FRAME_DURATION_MS] are raised to it (a 0 s entry is
     * undefined behaviour for the demuxer). The concat demuxer ignores the final
     * entry's duration unless the last file is listed again, so the closing
     * frame is repeated to give it its time.
     */
    fun build(entries: List<Entry>): String {
        require(entries.isNotEmpty()) { "A concat script needs at least one frame" }
        return buildString {
            entries.forEach { entry ->
                append("file '").append(entry.fileName).append("'\n")
                append(String.format(Locale.US, "duration %.3f\n", clampDuration(entry.durationMs) / 1000.0))
            }
            append("file '").append(entries.last().fileName).append("'\n")
        }
    }

    /** The duration actually written for a requested [durationMs]. */
    fun clampDuration(durationMs: Int): Int = durationMs.coerceAtLeast(MIN_FRAME_DURATION_MS)
}
