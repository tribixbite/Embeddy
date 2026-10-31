package app.embeddy.util

import app.embeddy.conversion.ConversionConfig
import app.embeddy.conversion.AUDIO_ONLY_BITRATE_BPS
import app.embeddy.conversion.OutputMediaFormat
import app.embeddy.conversion.VIDEO_AUDIO_BITRATE_BPS
import app.embeddy.conversion.bitrateForTargetSize
import app.embeddy.conversion.evenFloor
import app.embeddy.conversion.videoBitrateBps

/**
 * Shared BPP-based output size estimation utilities.
 * Used by both ConvertScreen (for output dimension preview) and
 * VideoTrimPlayer (for estimated output size display).
 */
object SizeEstimation {

    /**
     * Estimate output dimensions, mirroring the FFmpeg scale filters built by
     * `scaleFilters` in FfmpegCommands.kt:
     * - both exact dimensions set → exact crop output (scale-to-cover + crop)
     * - exactly one exact dimension → `scale=W:-2` / `scale=-2:H`: the other axis
     *   is derived from the aspect ratio and rounded to an even value like `-2` does
     * - maxDimension > 0 → `force_original_aspect_ratio=decrease` inside a
     *   `min(M,iw)` x `min(M,ih)` box, computed exactly like FFmpeg's
     *   scale_eval (nearest-rounded rescale, then the smaller of box and rescale)
     * - maxDimension == 0 → source size kept
     *
     * @param forVideo mirrors the even-dimension rules video outputs get:
     * `force_divisible_by=2` rounds DOWN, exact sizes are floored to even, and
     * a kept source size is truncated to even.
     */
    fun estimateOutputDimensions(
        inputWidth: Int,
        inputHeight: Int,
        config: ConversionConfig,
        forVideo: Boolean = false,
    ): Pair<Int, Int> {
        if (inputWidth <= 0 || inputHeight <= 0) return inputWidth to inputHeight
        val ew = if (forVideo && config.exactWidth > 0) evenFloor(config.exactWidth) else config.exactWidth
        val eh = if (forVideo && config.exactHeight > 0) evenFloor(config.exactHeight) else config.exactHeight
        return when {
            ew > 0 && eh > 0 -> ew to eh

            ew > 0 -> ew to evenRound(inputHeight.toDouble() * ew / inputWidth)

            eh > 0 -> evenRound(inputWidth.toDouble() * eh / inputHeight) to eh

            config.maxDimension > 0 -> {
                val boxW = minOf(config.maxDimension, inputWidth)
                val boxH = minOf(config.maxDimension, inputHeight)
                val tmpW = rescale(boxH.toLong(), inputWidth.toLong(), inputHeight.toLong())
                val tmpH = rescale(boxW.toLong(), inputHeight.toLong(), inputWidth.toLong())
                val w = minOf(tmpW, boxW)
                val h = minOf(tmpH, boxH)
                if (forVideo) evenFloor(w) to evenFloor(h) else w to h
            }

            forVideo -> evenFloor(inputWidth) to evenFloor(inputHeight)

            else -> inputWidth to inputHeight
        }
    }

    /** FFmpeg's av_rescale(a, b, c): a*b/c rounded to nearest, halves away from zero. */
    private fun rescale(a: Long, b: Long, c: Long): Int = ((a * b + c / 2) / c).toInt()

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

    /**
     * Format-aware output size estimate for a kept duration.
     *
     * Video outputs run in bitrate mode, so their size follows from the bitrate
     * the engine will actually request (the target-size bitrate when a target
     * is set, else the quality mapping) plus the audio track. The WebP
     * bits-per-pixel model below wildly misjudged MP4 output.
     */
    fun estimateConvertedBytes(
        config: ConversionConfig,
        inputWidth: Int,
        inputHeight: Int,
        keptMs: Long,
        hasAudio: Boolean,
        inputSizeBytes: Long = 0,
        totalDurationMs: Long = 0,
    ): Long {
        if (config.outputFormat == OutputMediaFormat.AUDIO) {
            return AUDIO_ONLY_BITRATE_BPS * keptMs.coerceAtLeast(0) / 8_000
        }
        if (config.outputFormat.isVideo) {
            if (keptMs <= 0) return 0
            val audioBps = if (hasAudio) VIDEO_AUDIO_BITRATE_BPS else 0L
            val videoBps = bitrateForTargetSize(config.targetSizeBytes, keptMs, audioBps) ?: run {
                val (w, h) = estimateOutputDimensions(inputWidth, inputHeight, config, forVideo = true)
                videoBitrateBps(w, h, config.estimationFps, config.startQuality)
            }
            return (videoBps + audioBps) * keptMs / 8_000
        }
        val (w, h) = estimateOutputDimensions(inputWidth, inputHeight, config)
        return estimateOutputBytes(
            width = w,
            height = h,
            durationMs = keptMs,
            fps = config.estimationFps,
            quality = config.startQuality,
            inputSizeBytes = inputSizeBytes,
            totalDurationMs = totalDurationMs,
        )
    }
}
