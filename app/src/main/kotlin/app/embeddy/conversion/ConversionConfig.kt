package app.embeddy.conversion

/**
 * A segment of video to keep during stitching.
 * Multiple segments allow users to remove sections from the middle of a video.
 */
data class TrimSegment(
    val startMs: Long,
    val endMs: Long,
    /**
     * Per-segment audio gain for video outputs: 0f (mute) to 2f (200%),
     * default 1f (unchanged). Ignored for WebP output, which is always silent.
     */
    val volume: Float = 1f,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/**
 * Merge overlapping or adjacent [TrimSegment]s so FFmpeg's concat filter never
 * receives duplicate frames. Segments are sorted by start time first.
 *
 * Volume semantics (documented choice):
 * - **Overlapping** segments always merge, FIRST-WINS: the earlier segment's
 *   volume covers the whole merged range (mirroring how the merge already keeps
 *   the earlier segment's start).
 * - **Adjacent** segments (`start == previous end`) merge only when their
 *   volumes are equal — a volume change at a cut point is an intentional audio
 *   edit and must survive the merge.
 * - **Zero/negative-length** segments are dropped: they contribute no frames
 *   and would otherwise become empty trim streams that abort FFmpeg's concat.
 *
 * Pure and top-level so the unit suite exercises the exact symbol production
 * uses (it previously lived as a private copy inside MainViewModel).
 */
fun mergeOverlappingSegments(segments: List<TrimSegment>): List<TrimSegment> {
    val nonEmpty = segments.filter { it.durationMs > 0 }
    if (nonEmpty.size <= 1) return nonEmpty
    val sorted = nonEmpty.sortedBy { it.startMs }
    return sorted.fold(mutableListOf<TrimSegment>()) { acc, seg ->
        val last = acc.lastOrNull()
        val overlaps = last != null && seg.startMs < last.endMs
        val adjacentSameVolume = last != null && seg.startMs == last.endMs && seg.volume == last.volume
        if (last != null && (overlaps || adjacentSameVolume)) {
            // Merge by extending the end; start AND volume stay first-wins.
            acc[acc.lastIndex] = last.copy(endMs = maxOf(last.endMs, seg.endMs))
        } else {
            acc.add(seg)
        }
        acc
    }
}

/**
 * Encoding PREFERENCES for the Convert tab, derived from a [Preset] or user
 * customization and persisted to DataStore.
 *
 * Per-file edit decisions (trim, segments, …) deliberately do NOT live here —
 * they belong to the picked input and are held in [EditSpec]. Mixing the two
 * meant a new file inherited the previous file's trim range and choosing a
 * preset silently wiped an in-progress edit.
 */
data class ConversionConfig(
    /** Longest output edge in pixels; [ORIGINAL_DIMENSION] (0) keeps the source size. */
    val maxDimension: Int = 720,
    /** Output frame rate; [ORIGINAL_FPS] (0) keeps the source rate. */
    val fps: Int = 12,
    val startQuality: Int = 70,
    val minQuality: Int = 50,
    val qualityStep: Int = 5,
    val targetSizeBytes: Long = 10_000_000L,
    val sharpen: Boolean = true,
    val loop: Int = 0,              // 0 = infinite loop
    val compressionLevel: Int = 4,  // WebP compression (0-6)
    val preset: Preset = Preset.DISCORD,
    // Exact output dimensions — 0 means "use maxDimension scaling" instead
    val exactWidth: Int = 0,
    val exactHeight: Int = 0,
    // Advanced FFmpeg encoding flags
    val colorSpace: ColorSpace = ColorSpace.AUTO, // Output color space / pixel format
    val denoiseStrength: Int = 0,           // hqdn3d denoise (0=off, 1-10 strength)
    val ditherMode: DitherMode = DitherMode.NONE, // Bayer/Floyd-Steinberg for palette reduction
    /** Output media format — see [OutputMediaFormat]. */
    val outputFormat: OutputMediaFormat = OutputMediaFormat.WEBP,
) {
    /**
     * Quality floor the adaptive loop may descend to.
     *
     * The quality slider lets the user pick a [startQuality] below a preset's
     * [minQuality]; without this clamp the adaptive loop in ConversionEngine would
     * never execute a single attempt and the conversion would fail outright.
     */
    val effectiveMinQuality: Int
        get() = minQuality.coerceAtMost(startQuality)

    /** Frame rate used for estimates when the output keeps the source rate. */
    val estimationFps: Int
        get() = if (fps > 0) fps else ASSUMED_SOURCE_FPS

    companion object {
        /** [maxDimension] value meaning "keep the source resolution". */
        const val ORIGINAL_DIMENSION = 0

        /** [fps] value meaning "keep the source frame rate". */
        const val ORIGINAL_FPS = 0

        /**
         * Frame rate assumed for progress/bitrate estimates when [fps] is
         * [ORIGINAL_FPS] — phone video is overwhelmingly 30 fps.
         */
        const val ASSUMED_SOURCE_FPS = 30

        fun fromPreset(preset: Preset): ConversionConfig = ConversionConfig(
            maxDimension = preset.maxDimension,
            fps = preset.fps,
            startQuality = preset.startQuality,
            minQuality = preset.minQuality,
            qualityStep = preset.qualityStep,
            targetSizeBytes = preset.targetSizeBytes,
            sharpen = preset.sharpen,
            preset = preset,
        )
    }
}

/**
 * Output color space / pixel format for encoding.
 *
 * Restricted to what libwebp_anim actually consumes (bgra/yuv420p/yuva420p):
 * the former YUV444/RGB entries mapped to yuv444p/rgb24, which FFmpeg silently
 * auto-substitutes with bgra — the settings were persisted no-ops. Previously
 * saved "YUV444"/"RGB" preference strings fall back to AUTO on restore.
 */
enum class ColorSpace(val label: String, val ffmpegValue: String) {
    AUTO("Auto", ""),
    YUV420("YUV 4:2:0", "yuv420p"),    // Standard, best compat
}

/**
 * Output media format for the Convert tab.
 *
 * WEBP and GIF are animated-image outputs; MP4 and ORIGINAL re-encode as video
 * (the bundled FFmpeg build has libopenh264 + native aac + libvpx + libopus,
 * but no libx264/x265, so stream copy or x264 are not options). ORIGINAL
 * matches the input container where recognized and falls back to MP4 — see
 * [resolveOutputTarget]. AUDIO extracts the soundtrack as AAC in .m4a.
 */
enum class OutputMediaFormat(val label: String) {
    WEBP("WebP"),
    GIF("GIF"),
    MP4("MP4"),
    ORIGINAL("Original"),
    AUDIO("Audio (M4A)");

    /** True for formats that produce a video container (and may carry audio). */
    val isVideo: Boolean
        get() = this == MP4 || this == ORIGINAL
}

/** Dithering mode for color quantization (useful for palette-constrained formats). */
enum class DitherMode(val label: String, val ffmpegValue: String) {
    NONE("None", ""),
    BAYER("Bayer", "bayer"),
    FLOYD_STEINBERG("Floyd-Steinberg", "floyd_steinberg"),
    SIERRA("Sierra", "sierra2_4a"),
}

/**
 * Why a [ConversionState.Error] happened, so the UI can choose localized copy
 * without the state machine depending on Android resources.
 */
enum class ErrorKind {
    /** The picked input could not be opened or probed. */
    READ_FILE,

    /** Encoding failed; [ConversionState.Error.message] carries FFmpeg's reason. */
    CONVERSION,
}

/** Represents the current state of a conversion operation. */
sealed interface ConversionState {
    data object Idle : ConversionState

    /** Probing a freshly picked input. */
    data object Loading : ConversionState

    data class Ready(
        val source: SourceMedia,
        /** Transient message (e.g. a failed preview) shown alongside the file info. */
        val notice: String? = null,
    ) : ConversionState {
        /** The first (or only) clip — what the single-file UI renders. */
        val primary: SourceClip get() = source.clips.first()
        val inputUri: String get() = primary.uri
        val fileName: String get() = primary.fileName
        val fileSize: Long get() = primary.fileSize
        val durationMs: Long get() = primary.durationMs
        val width: Int get() = primary.width
        val height: Int get() = primary.height
        val hasAudio: Boolean get() = source.clips.any { it.hasAudio }
    }

    data class Converting(
        val progress: Float = 0f,       // 0..1
        val currentQuality: Int = 70,
        val attempt: Int = 1,
        val elapsedMs: Long = 0,
    ) : ConversionState

    data class Done(
        val outputPath: String,
        val outputSizeBytes: Long,
        val qualityUsed: Int,
        val inputFileName: String,
        /** The input this output came from, so "edit again" keeps the file. */
        val returnTo: Ready? = null,
    ) : ConversionState

    /** Output was produced but exceeds target size — user decides to keep or retry. */
    data class SizeWarning(
        val outputPath: String,
        val outputSizeBytes: Long,
        val targetSizeBytes: Long,
        val qualityUsed: Int,
        val inputFileName: String,
        /** "Adjust & retry" returns here instead of dropping the file. */
        val returnTo: Ready? = null,
    ) : ConversionState

    data class Error(
        val message: String,
        val kind: ErrorKind = ErrorKind.CONVERSION,
        /** When set, "Try again" returns to this input instead of Idle. */
        val returnTo: Ready? = null,
    ) : ConversionState

    /** Generating a short preview clip with current settings. */
    data class Previewing(
        val progress: Float = 0f,
        val elapsedMs: Long = 0,
        /** Stash the Ready state so cancelling always has somewhere to return to. */
        val previousReady: Ready,
    ) : ConversionState

    /** Preview clip generated, ready to display. */
    data class PreviewReady(
        val previewPath: String,
        val fileSizeBytes: Long,
        /** Stash the Ready state so we can restore it after dismissing preview. */
        val previousReady: Ready,
    ) : ConversionState
}
