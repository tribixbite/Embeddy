package app.embeddy.conversion

/**
 * One picked input file plus the metadata the probe found for it.
 *
 * Dimensions are DISPLAY dimensions (rotation metadata already applied, the way
 * FFmpeg autorotates on decode), so every downstream estimate matches the output.
 */
data class SourceClip(
    /** content:// (or file://) URI string as returned by the picker. */
    val uri: String,
    val fileName: String,
    /** Byte size from the provider, 0 when the provider reports none. */
    val fileSize: Long,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    /** True when the probe found an audio track (drives the volume/mute UI). */
    val hasAudio: Boolean = false,
)

/** How the picked inputs combine into one output. */
enum class SourceKind {
    /** A single video, GIF or animated WebP. */
    SINGLE,

    /** Several separate videos/GIFs played back to back. */
    JOIN,

    /** Still images turned into an animation, one frame each. */
    IMAGE_SEQUENCE,
}

/** Everything the user picked for the Convert tab, in output order. */
data class SourceMedia(
    val kind: SourceKind,
    val clips: List<SourceClip>,
) {
    init {
        require(clips.isNotEmpty()) { "SourceMedia needs at least one clip" }
    }

    companion object {
        /** Convenience for the common one-file case. */
        fun single(clip: SourceClip) = SourceMedia(SourceKind.SINGLE, listOf(clip))
    }
}

/**
 * Per-input edit decisions: what to keep and how to transform it.
 *
 * Unlike [ConversionConfig] (persisted encoding preferences), an EditSpec
 * belongs to one picked input — it is reset when a new file is picked, survives
 * a preset change, and is saved in SavedStateHandle so process death does not
 * lose a multi-segment edit.
 */
data class EditSpec(
    /** Legacy single-range trim start; 0 = from the beginning. */
    val trimStartMs: Long = 0,
    /** Legacy single-range trim end; 0 = to the end of the source. */
    val trimEndMs: Long = 0,
    /** Stitch mode: ranges to KEEP. When non-empty, overrides the trim range. */
    val segments: List<TrimSegment> = emptyList(),
    /** Drop the audio track from video outputs. */
    val muted: Boolean = false,
    /** Playback speed factor, [MIN_SPEED]..[MAX_SPEED]; 1 = unchanged. */
    val speed: Float = 1f,
    /** Clockwise rotation applied to the output: 0, 90, 180 or 270. */
    val rotationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    /** Region of the (display-oriented) source to keep; null = whole frame. */
    val crop: CropRect? = null,
    /** Play the output backwards (only for outputs up to [REVERSE_MAX_OUTPUT_MS]). */
    val reverse: Boolean = false,
    /**
     * JOIN: the kept range of each clip, index-aligned with the source's clips.
     * A missing entry keeps that clip whole.
     */
    val clipRanges: List<TrimSegment> = emptyList(),
    /** IMAGE_SEQUENCE: how long each image stays on screen. */
    val frameDurationMs: Int = DEFAULT_FRAME_DURATION_MS,
) {
    /**
     * The source ranges that end up in the output, in output order.
     *
     * Segments are merged (overlaps collapse, zero-length ranges drop); without
     * segments this is the single trim range, with an open end resolved to
     * [sourceDurationMs]. Returns an empty list when nothing would be kept.
     * The single place every consumer (command builder, estimates, preview
     * window) asks "what is kept?", so they cannot disagree.
     */
    fun keptRanges(sourceDurationMs: Long): List<TrimSegment> {
        if (segments.isNotEmpty()) return mergeOverlappingSegments(segments)
        val start = trimStartMs.coerceAtLeast(0)
        val end = when {
            trimEndMs > 0 -> trimEndMs
            sourceDurationMs > 0 -> sourceDurationMs
            else -> 0L
        }
        // An unknown source duration with no explicit end means "everything":
        // represent it as an open range the builder emits without -t.
        if (end == 0L) return listOf(TrimSegment(start, 0L))
        return if (end > start) listOf(TrimSegment(start, end)) else emptyList()
    }

    /** Total source time kept, in ms (0 when unknown). */
    fun keptDurationMs(sourceDurationMs: Long): Long =
        keptRanges(sourceDurationMs).sumOf { it.durationMs }

    /**
     * JOIN: the kept range of clip [index] whose full length is
     * [clipDurationMs]. Falls back to the whole clip when no valid range is set.
     */
    fun clipRange(index: Int, clipDurationMs: Long): TrimSegment {
        val range = clipRanges.getOrNull(index)
        return if (range != null && range.durationMs > 0) range else TrimSegment(0, clipDurationMs)
    }

    /** [speed] clamped to the supported range. */
    val effectiveSpeed: Float get() = speed.coerceIn(MIN_SPEED, MAX_SPEED)

    /** Rotation normalized to 0/90/180/270. */
    val normalizedRotation: Int get() = ((rotationDegrees / 90 * 90) % 360 + 360) % 360

    /** True when the user cut anything away from the full source. */
    fun isTrimmed(sourceDurationMs: Long): Boolean {
        val ranges = keptRanges(sourceDurationMs)
        if (ranges.size != 1) return true
        val only = ranges.first()
        return only.startMs > 0 || (only.endMs in 1 until sourceDurationMs)
    }
}

/** Slowest supported playback speed. */
const val MIN_SPEED = 0.25f

/** Fastest supported playback speed. */
const val MAX_SPEED = 4f

/** Speed presets offered in the editor. */
val SPEED_PRESETS = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)

/**
 * Longest output that may be reversed. FFmpeg's `reverse`/`areverse` filters
 * hold every frame of the clip in memory before emitting the first one, so
 * reversing is limited to short clips (10 s of 720p30 is ~400 MB of frames).
 */
const val REVERSE_MAX_OUTPUT_MS = 10_000L

/** Default on-screen time per image in an image sequence. */
const val DEFAULT_FRAME_DURATION_MS = 500

/**
 * A crop window as fractions (0..1) of the display-oriented source frame.
 * Fractions rather than pixels so the rect is independent of preview size.
 */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    /** Ordered, clamped to the frame, and at least [MIN_CROP_FRACTION] on each side. */
    fun normalized(): CropRect {
        var l = minOf(left, right).coerceIn(0f, 1f)
        var r = maxOf(left, right).coerceIn(0f, 1f)
        var t = minOf(top, bottom).coerceIn(0f, 1f)
        var b = maxOf(top, bottom).coerceIn(0f, 1f)
        if (r - l < MIN_CROP_FRACTION) { r = (l + MIN_CROP_FRACTION).coerceAtMost(1f); l = r - MIN_CROP_FRACTION }
        if (b - t < MIN_CROP_FRACTION) { b = (t + MIN_CROP_FRACTION).coerceAtMost(1f); t = b - MIN_CROP_FRACTION }
        return CropRect(l, t, r, b)
    }

    /** True when the rect keeps (practically) the whole frame. */
    val isFullFrame: Boolean
        get() = left <= 0.001f && top <= 0.001f && right >= 0.999f && bottom >= 0.999f

    companion object {
        /** Smallest crop edge, as a fraction of the frame. */
        const val MIN_CROP_FRACTION = 0.05f

        val FULL = CropRect(0f, 0f, 1f, 1f)
    }
}

/** Pixel crop window: size and offset, all even (yuv420p chroma alignment). */
data class CropPixels(val width: Int, val height: Int, val x: Int, val y: Int)

/**
 * Convert [crop] to even pixel values for a [width]x[height] frame. FFmpeg's
 * crop rounds odd values for subsampled formats anyway; computing them here
 * keeps the size estimate and the command identical.
 */
fun cropPixels(crop: CropRect, width: Int, height: Int): CropPixels {
    val c = crop.normalized()
    fun even(v: Int) = v / 2 * 2
    val x = even((c.left * width).toInt()).coerceIn(0, maxOf(0, width - 2))
    val y = even((c.top * height).toInt()).coerceIn(0, maxOf(0, height - 2))
    val w = even(((c.right - c.left) * width).toInt()).coerceIn(2, maxOf(2, even(width - x)))
    val h = even(((c.bottom - c.top) * height).toInt()).coerceIn(2, maxOf(2, even(height - y)))
    return CropPixels(w, h, x, y)
}

/**
 * Source dimensions after the edit's crop and rotation — what the scale stage
 * of the filter chain sees. Unknown (0) dimensions pass through.
 */
fun editedDimensions(width: Int, height: Int, edit: EditSpec): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return width to height
    val crop = edit.crop?.takeUnless { it.isFullFrame }
    val (w, h) = if (crop != null) cropPixels(crop, width, height).let { it.width to it.height } else width to height
    return if (edit.normalizedRotation % 180 == 90) h to w else w to h
}
