package app.embeddy.conversion

/**
 * Mutable conversion settings derived from a [Preset] or user customization.
 */
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
) {
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

    data class Error(val message: String) : ConversionState
}
