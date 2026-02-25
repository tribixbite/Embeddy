package app.embeddy.conversion

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Wraps FFmpeg-kit to convert media files to animated WebP.
 * Implements the adaptive-quality algorithm from discwebp:
 * try at startQuality, reduce by qualityStep until file fits targetSizeBytes or minQuality reached.
 */
class ConversionEngine(private val context: Context) {

    private val cacheDir: File
        get() = File(context.cacheDir, "converted").also { it.mkdirs() }

    private val tempDir: File
        get() = File(context.cacheDir, "temp").also { it.mkdirs() }

    /** Extract basic metadata from the input URI. */
    suspend fun probeInput(uri: Uri): MediaInfo = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            val bitrate = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_BITRATE
            )?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            val mimeType = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_MIMETYPE
            ) ?: ""
            val frameCount = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT
            )?.toIntOrNull() ?: 0
            MediaInfo(
                width = width,
                height = height,
                durationMs = durationMs,
                bitrate = bitrate,
                rotation = rotation,
                mimeType = mimeType,
                frameCount = frameCount,
            )
        } finally {
            retriever.release()
        }
    }

    /**
     * Convert input URI to animated WebP using adaptive quality.
     * Emits [ConversionProgress] updates and returns the output file path on success.
     */
    fun convert(
        inputUri: Uri,
        config: ConversionConfig,
        outputName: String,
    ): Flow<ConversionProgress> = callbackFlow {
        // Copy input to a temp file so FFmpeg can access it directly
        val inputFile = copyUriToTemp(inputUri)
        val tempOutput = File(tempDir, "temp_${UUID.randomUUID()}.webp")
        val finalOutput = File(cacheDir, "${outputName}_embeddy.webp")

        var quality = config.startQuality
        var attempt = 1
        val startTime = System.currentTimeMillis()
        Timber.d("Starting conversion: %s → %s (q=%d, target=%d bytes)",
            inputFile.name, finalOutput.name, quality, config.targetSizeBytes)

        // Get duration for progress calculation.
        // In stitch mode, FFmpeg outputs only the kept segments so use their
        // combined duration; otherwise fall back to the probed full duration.
        val durationMs = config.totalKeptDurationMs.takeIf { it > 0 }
            ?: try {
                probeInput(inputUri).durationMs
            } catch (_: Exception) {
                0L
            }

        try {
            // Track the best (smallest) successful output across all attempts.
            // If a later attempt fails, we still have a usable result.
            var bestOutputSize = Long.MAX_VALUE
            var bestQuality = config.startQuality
            var hasAnyOutput = false

            while (quality >= config.minQuality) {
                send(ConversionProgress.Attempt(quality, attempt))

                val command = buildFfmpegCommand(
                    inputPath = inputFile.absolutePath,
                    outputPath = tempOutput.absolutePath,
                    config = config,
                    quality = quality,
                )
                Timber.d("FFmpeg command (q=%d): %s", quality, command)

                val success = executeFfmpeg(command) { stats ->
                    val progress = if (durationMs > 0) {
                        (stats.time.toFloat() / durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    trySend(
                        ConversionProgress.Progress(
                            fraction = progress,
                            currentQuality = quality,
                            attempt = attempt,
                            elapsedMs = System.currentTimeMillis() - startTime,
                        )
                    )
                }

                if (!success) {
                    // FFmpeg failed at this quality — don't send Failed yet,
                    // we may still have output from a prior successful attempt
                    break
                }

                val fileSize = tempOutput.length()
                if (fileSize > 0) {
                    // Save best output so far (smallest file = lowest quality)
                    tempOutput.copyTo(finalOutput, overwrite = true)
                    bestOutputSize = fileSize
                    bestQuality = quality
                    hasAnyOutput = true
                }

                if (fileSize <= config.targetSizeBytes) {
                    // Fits the target — done
                    tempOutput.delete()
                    inputFile.delete()
                    send(
                        ConversionProgress.Complete(
                            outputPath = finalOutput.absolutePath,
                            fileSizeBytes = finalOutput.length(),
                            qualityUsed = quality,
                        )
                    )
                    close()
                    return@callbackFlow
                }

                // Doesn't fit — reduce quality and retry
                val sizeMb = String.format("%.1f", fileSize / 1_000_000.0)
                send(ConversionProgress.SizeExceeded(fileSize, quality, sizeMb))
                quality -= config.qualityStep
                attempt++
            }

            // Loop ended: either exhausted quality range or FFmpeg error.
            // Always offer the best output we managed to produce.
            tempOutput.delete()
            inputFile.delete()
            if (hasAnyOutput) {
                send(
                    ConversionProgress.CompletedOversize(
                        outputPath = finalOutput.absolutePath,
                        fileSizeBytes = bestOutputSize,
                        targetSizeBytes = config.targetSizeBytes,
                        qualityUsed = bestQuality,
                    )
                )
            } else {
                send(ConversionProgress.Failed("FFmpeg could not produce output — try a different file or shorter trim"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Conversion failed")
            send(ConversionProgress.Failed(e.message ?: "Unknown error"))
        } finally {
            tempOutput.delete()
            inputFile.delete()
        }

        close()
    }.flowOn(Dispatchers.IO)

    /**
     * Build the core video filter chain (scale, fps, denoise, sharpen, dither).
     * Shared between single-segment and multi-segment (stitching) modes.
     */
    private fun buildVideoFilters(config: ConversionConfig): String {
        return buildList {
            // Scaling: exact dimensions take precedence over maxDimension
            if (config.exactWidth > 0 && config.exactHeight > 0) {
                add("scale=${config.exactWidth}:${config.exactHeight}:force_original_aspect_ratio=increase:flags=lanczos")
                add("crop=${config.exactWidth}:${config.exactHeight}")
            } else if (config.exactWidth > 0) {
                add("scale=${config.exactWidth}:-2:flags=lanczos")
            } else if (config.exactHeight > 0) {
                add("scale=-2:${config.exactHeight}:flags=lanczos")
            } else {
                add("scale='min(${config.maxDimension},iw)':'min(${config.maxDimension},ih)':force_original_aspect_ratio=decrease:flags=lanczos")
            }
            add("fps=${config.fps}")
            if (config.denoiseStrength > 0) {
                val strength = config.denoiseStrength.coerceIn(1, 10)
                add("hqdn3d=$strength:$strength:${strength / 2}:${strength / 2}")
            }
            if (config.sharpen) {
                add("unsharp=5:5:1.2:5:5:0.6")
            }
            if (config.ditherMode != DitherMode.NONE) {
                add("split[a][b];[a]palettegen[p];[b][p]paletteuse=dither=${config.ditherMode.ffmpegValue}")
            }
        }.joinToString(",")
    }

    /** Append encoder flags common to both single and multi-segment modes. */
    private fun StringBuilder.appendEncoderFlags(config: ConversionConfig, quality: Int) {
        append("-c:v libwebp_anim ")
        append("-quality $quality ")
        append("-compression_level ${config.compressionLevel} ")
        append("-loop ${config.loop} ")
        append("-an ")
        append("-vsync vfr ")
        if (config.colorSpace != ColorSpace.AUTO) {
            append("-pix_fmt ${config.colorSpace.ffmpegValue} ")
        }
        if (config.keyframeInterval > 0) {
            append("-g ${config.keyframeInterval} ")
        }
    }

    /**
     * Build the FFmpeg command matching the discwebp algorithm.
     * Supports:
     * - Single trim (trimStartMs/trimEndMs)
     * - Multi-segment stitching via concat filter (segments list)
     * - Exact crop, denoise, sharpen, dither, color space, keyframe interval
     */
    private fun buildFfmpegCommand(
        inputPath: String,
        outputPath: String,
        config: ConversionConfig,
        quality: Int,
    ): String {
        val filters = buildVideoFilters(config)

        // Multi-segment stitching mode: use complex filtergraph with concat
        if (config.segments.size > 1) {
            return buildStitchCommand(inputPath, outputPath, config, quality, filters)
        }

        // Single-segment mode (legacy trim or single segment)
        val effectiveStart = config.segments.firstOrNull()?.startMs ?: config.trimStartMs
        val effectiveEnd = config.segments.firstOrNull()?.endMs ?: config.trimEndMs

        return buildString {
            append("-y ")
            if (effectiveStart > 0) {
                val ss = String.format("%.3f", effectiveStart / 1000.0)
                append("-ss $ss ")
            }
            append("-i \"$inputPath\" ")
            if (effectiveEnd > 0) {
                val endSec = (effectiveEnd - effectiveStart) / 1000.0
                val to = String.format("%.3f", endSec)
                append("-to $to ")
            }
            append("-vf \"$filters\" ")
            appendEncoderFlags(config, quality)
            append("\"$outputPath\"")
        }
    }

    /**
     * Build an FFmpeg command that stitches multiple segments using the concat filter.
     * Each segment is trimmed from the same input, concatenated, THEN filtered.
     *
     * Filters are applied AFTER concat to avoid FFmpeg label collisions — some
     * filters (e.g. palettegen/paletteuse for dithering) use internal labels like
     * [a][b][p] that would conflict if duplicated per segment.
     *
     * Complex filtergraph structure:
     *   [0:v]trim=start:end,setpts=PTS-STARTPTS[v0];
     *   [0:v]trim=start:end,setpts=PTS-STARTPTS[v1];
     *   [v0][v1]concat=n=2:v=1:a=0[cat];[cat]<filters>[vout]
     */
    private fun buildStitchCommand(
        inputPath: String,
        outputPath: String,
        config: ConversionConfig,
        quality: Int,
        filters: String,
    ): String {
        val n = config.segments.size

        val filterGraph = buildString {
            config.segments.forEachIndexed { i, seg ->
                val start = String.format("%.3f", seg.startMs / 1000.0)
                val end = String.format("%.3f", seg.endMs / 1000.0)
                // Trim each segment and reset PTS (no per-segment filters)
                append("[0:v]trim=$start:$end,setpts=PTS-STARTPTS[v$i];")
            }
            // Concatenate all segments, then apply filters to the combined stream
            val inputs = (0 until n).joinToString("") { "[v$it]" }
            append("${inputs}concat=n=$n:v=1:a=0[cat];[cat]$filters[vout]")
        }

        return buildString {
            append("-y ")
            append("-i \"$inputPath\" ")
            append("-filter_complex \"$filterGraph\" ")
            append("-map \"[vout]\" ")
            appendEncoderFlags(config, quality)
            append("\"$outputPath\"")
        }
    }

    /** Execute an FFmpeg command with a statistics callback, returns true on success. */
    private suspend fun executeFfmpeg(
        command: String,
        onStatistics: (Statistics) -> Unit,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val session = FFmpegKit.executeAsync(
            command,
            { session ->
                val returnCode = session.returnCode
                val success = ReturnCode.isSuccess(returnCode)
                if (!success) {
                    Timber.e("FFmpeg FAILED (rc=%s): %s", returnCode, session.output?.takeLast(500))
                }
                if (cont.isActive) {
                    cont.resume(success)
                }
            },
            { log -> Timber.v("FFmpeg: %s", log.message) },
            { stats -> onStatistics(stats) },
        )

        cont.invokeOnCancellation {
            session.cancel()
        }
    }

    /** Copy a content:// URI to a temp file for FFmpeg access. */
    private suspend fun copyUriToTemp(uri: Uri): File = withContext(Dispatchers.IO) {
        val tempFile = File(tempDir, "input_${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Cannot open URI: $uri")
        tempFile
    }

    /**
     * Generate a short preview clip (~3 seconds) using the current settings.
     * Single-pass at the given quality — no adaptive loop, no target size check.
     * Returns the output file path on success, or null on failure.
     */
    fun generatePreview(
        inputUri: Uri,
        config: ConversionConfig,
        previewDurationSec: Double = 3.0,
    ): Flow<ConversionProgress> = callbackFlow {
        val inputFile = copyUriToTemp(inputUri)
        val previewOutput = File(tempDir, "preview_${UUID.randomUUID()}.webp")

        try {
            // Determine the preview start point from trim/segment settings
            val startMs = config.segments.firstOrNull()?.startMs
                ?: config.trimStartMs.takeIf { it > 0 }
                ?: 0L

            val previewConfig = config.copy(
                // Override trim to only produce previewDurationSec of output
                trimStartMs = startMs,
                trimEndMs = startMs + (previewDurationSec * 1000).toLong(),
                segments = emptyList(), // Force single-segment mode for preview
            )

            val durationMs = (previewDurationSec * 1000).toLong()

            send(ConversionProgress.Attempt(config.startQuality, 1))

            val command = buildFfmpegCommand(
                inputPath = inputFile.absolutePath,
                outputPath = previewOutput.absolutePath,
                config = previewConfig,
                quality = config.startQuality,
            )
            Timber.d("Preview FFmpeg command (q=%d): %s", config.startQuality, command)

            val success = executeFfmpeg(command) { stats ->
                val progress = if (durationMs > 0) {
                    (stats.time.toFloat() / durationMs).coerceIn(0f, 1f)
                } else 0f
                trySend(
                    ConversionProgress.Progress(
                        fraction = progress,
                        currentQuality = config.startQuality,
                        attempt = 1,
                        elapsedMs = 0,
                    )
                )
            }

            if (success && previewOutput.exists() && previewOutput.length() > 0) {
                send(
                    ConversionProgress.Complete(
                        outputPath = previewOutput.absolutePath,
                        fileSizeBytes = previewOutput.length(),
                        qualityUsed = config.startQuality,
                    )
                )
            } else {
                previewOutput.delete()
                send(ConversionProgress.Failed("Preview generation failed"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Preview generation failed")
            previewOutput.delete()
            send(ConversionProgress.Failed(e.message ?: "Unknown error"))
        } finally {
            inputFile.delete()
            // Note: previewOutput is NOT deleted here — caller owns cleanup
        }

        close()
    }.flowOn(Dispatchers.IO)

    /** Cleanup old converted files older than 24 hours. */
    fun cleanupOldFiles() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        cacheDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        tempDir.listFiles()?.forEach { it.delete() }
    }
}

data class MediaInfo(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val bitrate: Int = 0,
    val rotation: Int = 0,
    val mimeType: String = "",
    val frameCount: Int = 0,
)

/** Progress events emitted during conversion. */
sealed interface ConversionProgress {
    data class Attempt(val quality: Int, val attemptNumber: Int) : ConversionProgress
    data class Progress(
        val fraction: Float,
        val currentQuality: Int,
        val attempt: Int,
        val elapsedMs: Long,
    ) : ConversionProgress
    data class SizeExceeded(
        val actualBytes: Long,
        val quality: Int,
        val sizeMbFormatted: String,
    ) : ConversionProgress
    data class Complete(
        val outputPath: String,
        val fileSizeBytes: Long,
        val qualityUsed: Int,
    ) : ConversionProgress
    /** Conversion produced output but it exceeds the target size. */
    data class CompletedOversize(
        val outputPath: String,
        val fileSizeBytes: Long,
        val targetSizeBytes: Long,
        val qualityUsed: Int,
    ) : ConversionProgress
    data class Failed(val message: String) : ConversionProgress
}
