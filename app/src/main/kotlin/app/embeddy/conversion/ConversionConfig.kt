package app.embeddy.conversion

/**
 * Mutable conversion settings derived from a [Preset] or user customization.
 */
/**
 * A segment of video to keep during stitching.
 * Multiple segments allow users to remove sections from the middle of a video.
 */
data class TrimSegment(
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

data class ConversionConfig(
    val maxDimension: Int = 720,
    val fps: Int = 12,
    val startQuality: Int = 70,
    val minQuality: Int = 50,
    val qualityStep: Int = 5,
    val targetSizeBytes: Long = 10_000_000L,
    val sharpen: Boolean = true,
    val loop: Int = 0,              // 0 = infinite loop
    val compressionLevel: Int = 4,  // WebP compression (0-6)
    val preset: Preset = Preset.DISCORD,
    val trimStartMs: Long = 0,      // 0 = no trim (legacy single-trim)
    val trimEndMs: Long = 0,        // 0 = use full duration (legacy single-trim)
    // Multi-segment stitching: when non-empty, overrides trimStartMs/trimEndMs.
    // Each segment defines a portion to KEEP; gaps between segments are removed.
    val segments: List<TrimSegment> = emptyList(),
    // Exact output dimensions — 0 means "use maxDimension scaling" instead
    val exactWidth: Int = 0,
    val exactHeight: Int = 0,
    // Advanced FFmpeg encoding flags
    val colorSpace: ColorSpace = ColorSpace.AUTO, // Output color space / pixel format
    val denoiseStrength: Int = 0,           // hqdn3d denoise (0=off, 1-10 strength)
    val ditherMode: DitherMode = DitherMode.NONE, // Bayer/Floyd-Steinberg for palette reduction
    val keyframeInterval: Int = 0,          // -g flag: force keyframe every N frames (0=auto)
) {
    /** Total kept duration in ms across all segments (or single trim range). */
    val totalKeptDurationMs: Long
        get() = if (segments.isNotEmpty()) {
            segments.sumOf { it.durationMs }
        } else if (trimEndMs > trimStartMs) {
            trimEndMs - trimStartMs
        } else 0L
    companion object {
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

/** Output color space / pixel format for encoding. */
enum class ColorSpace(val label: String, val ffmpegValue: String) {
    AUTO("Auto", ""),
    YUV420("YUV 4:2:0", "yuv420p"),    // Standard, best compat
    YUV444("YUV 4:4:4", "yuv444p"),    // Higher chroma fidelity, larger
    RGB("RGB", "rgb24"),                // Lossless color, largest
}

/** Dithering mode for color quantization (useful for palette-constrained formats). */
enum class DitherMode(val label: String, val ffmpegValue: String) {
    NONE("None", ""),
    BAYER("Bayer", "bayer"),
    FLOYD_STEINBERG("Floyd-Steinberg", "floyd_steinberg"),
    SIERRA("Sierra", "sierra2_4a"),
}

/** Represents the current state of a conversion operation. */
sealed interface ConversionState {
    data object Idle : ConversionState

    data class Picking(val message: String = "") : ConversionState

    data class Ready(
        val inputUri: String,
        val fileName: String,
        val fileSize: Long,
        val durationMs: Long = 0,
        val width: Int = 0,
        val height: Int = 0,
    ) : ConversionState

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
    ) : ConversionState

    /** Output was produced but exceeds target size — user decides to keep or retry. */
    data class SizeWarning(
        val outputPath: String,
        val outputSizeBytes: Long,
        val targetSizeBytes: Long,
        val qualityUsed: Int,
        val inputFileName: String,
    ) : ConversionState

    data class Error(val message: String) : ConversionState

    /** Generating a short preview clip with current settings. */
    data class Previewing(
        val progress: Float = 0f,
        val elapsedMs: Long = 0,
    ) : ConversionState

    /** Preview clip generated, ready to display. */
    data class PreviewReady(
        val previewPath: String,
        val fileSizeBytes: Long,
        /** Stash the Ready state so we can restore it after dismissing preview. */
        val previousReady: Ready,
    ) : ConversionState
}
