package app.embeddy.util

import app.embeddy.conversion.ConversionConfig

/**
 * Shared BPP-based output size estimation utilities.
 * Used by both ConvertScreen (for output dimension preview) and
 * VideoTrimPlayer (for estimated output size display).
 */
object SizeEstimation {

    /**
     * Estimate output dimensions, mirroring the FFmpeg scale filters built by
     * ConversionEngine.buildVideoFilters:
     * - both exact dimensions set → exact crop output (scale-to-cover + crop)
     * - exactly one exact dimension → `scale=W:-2` / `scale=-2:H`: the other axis
     *   is derived from the aspect ratio and rounded to an even value like `-2` does
     * - otherwise `force_original_aspect_ratio=decrease`, which scales when EITHER
     *   axis exceeds maxDimension using one shared factor for both axes
     */
    fun estimateOutputDimensions(
        inputWidth: Int,
        inputHeight: Int,
        config: ConversionConfig,
    ): Pair<Int, Int> {
        if (inputWidth <= 0 || inputHeight <= 0) return inputWidth to inputHeight
        return when {
            config.exactWidth > 0 && config.exactHeight > 0 ->
                config.exactWidth to config.exactHeight

            config.exactWidth > 0 ->
                config.exactWidth to
                    evenRound(inputHeight.toDouble() * config.exactWidth / inputWidth)

            config.exactHeight > 0 ->
                evenRound(inputWidth.toDouble() * config.exactHeight / inputHeight) to
                    config.exactHeight

            config.maxDimension > 0 && maxOf(inputWidth, inputHeight) > config.maxDimension -> {
                val scale = config.maxDimension.toFloat() / maxOf(inputWidth, inputHeight)
                (inputWidth * scale).toInt() to (inputHeight * scale).toInt()
            }

            else -> inputWidth to inputHeight
        }
    }

    /**
     * Estimate effective output width given input dimensions and config.
     * Accounts for exact dimensions, aspect-derived scaling, and maxDimension.
     */
    fun estimateOutputWidth(inputWidth: Int, inputHeight: Int, config: ConversionConfig): Int =
        estimateOutputDimensions(inputWidth, inputHeight, config).first

    /**
     * Estimate effective output height given input dimensions and config.
     */
    fun estimateOutputHeight(inputWidth: Int, inputHeight: Int, config: ConversionConfig): Int =
        estimateOutputDimensions(inputWidth, inputHeight, config).second

    /** FFmpeg's `-2` keeps aspect ratio but forces the dimension to an even value. */
    private fun evenRound(value: Double): Int = (Math.round(value / 2.0) * 2).toInt()

    /**
     * Estimate output file size in bytes using BPP (bits per pixel) model.
     * @param width Output width in pixels
     * @param height Output height in pixels
     * @param durationMs Total kept duration in milliseconds
     * @param fps Target frames per second
     * @param quality Encoding quality (1-100)
     * @param inputSizeBytes Original file size, used as fallback heuristic
     * @param totalDurationMs Full video duration (for ratio-based fallback)
     */
    fun estimateOutputBytes(
        width: Int,
        height: Int,
        durationMs: Long,
        fps: Int,
        quality: Int,
        inputSizeBytes: Long = 0,
        totalDurationMs: Long = 0,
    ): Long {
        if (width > 0 && height > 0 && durationMs > 0) {
            val bpp = 0.05f + (quality / 100f) * 0.25f
            val totalFrames = (durationMs / 1000f * fps).toLong().coerceAtLeast(1)
            val totalPixels = width.toLong() * height.toLong()
            return (totalPixels * totalFrames * bpp / 8f).toLong()
        }
        // Fallback: ratio of input size scaled by kept duration
        if (totalDurationMs > 0 && inputSizeBytes > 0) {
            return (inputSizeBytes * durationMs.toFloat() / totalDurationMs * 0.3f).toLong()
        }
        return inputSizeBytes
    }
}
