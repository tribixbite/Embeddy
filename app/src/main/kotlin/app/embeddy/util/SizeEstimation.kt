package app.embeddy.util

import app.embeddy.conversion.ConversionConfig

/**
 * Shared BPP-based output size estimation utilities.
 * Used by both ConvertScreen (for output dimension preview) and
 * VideoTrimPlayer (for estimated output size display).
 */
object SizeEstimation {

    /**
     * Estimate effective output width given input dimensions and config.
     * Accounts for exact dimensions, maxDimension scaling.
     */
    fun estimateOutputWidth(inputWidth: Int, inputHeight: Int, config: ConversionConfig): Int = when {
        config.exactWidth > 0 -> config.exactWidth
        inputWidth > config.maxDimension && config.maxDimension > 0 -> {
            val scale = minOf(
                config.maxDimension.toFloat() / inputWidth,
                config.maxDimension.toFloat() / inputHeight,
            )
            (inputWidth * scale).toInt()
        }
        else -> inputWidth
    }

    /**
     * Estimate effective output height given input dimensions and config.
     */
    fun estimateOutputHeight(inputWidth: Int, inputHeight: Int, config: ConversionConfig): Int = when {
        config.exactHeight > 0 -> config.exactHeight
        inputHeight > config.maxDimension && config.maxDimension > 0 -> {
            val scale = minOf(
                config.maxDimension.toFloat() / inputWidth,
                config.maxDimension.toFloat() / inputHeight,
            )
            (inputHeight * scale).toInt()
        }
        else -> inputHeight
    }

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
