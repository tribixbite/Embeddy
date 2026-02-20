package app.embeddy.conversion

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
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
            MediaInfo(width = width, height = height, durationMs = durationMs)
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
        val tempOutput = File(tempDir, "temp_output.webp")
        val finalOutput = File(cacheDir, "${outputName}_embeddy.webp")

        var quality = config.startQuality
        var attempt = 1
        val startTime = System.currentTimeMillis()

        // Get duration for progress calculation
        val durationMs = try {
            val info = probeInput(inputUri)
            info.durationMs
        } catch (_: Exception) {
            0L
        }

        try {
            while (quality >= config.minQuality) {
                send(ConversionProgress.Attempt(quality, attempt))

                val command = buildFfmpegCommand(
                    inputPath = inputFile.absolutePath,
                    outputPath = tempOutput.absolutePath,
                    config = config,
                    quality = quality,
                )

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
                    send(ConversionProgress.Failed("FFmpeg returned error at quality $quality"))
                    break
                }

                val fileSize = tempOutput.length()
                if (fileSize <= config.targetSizeBytes) {
                    // Fits! Move to final location
                    tempOutput.copyTo(finalOutput, overwrite = true)
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

            // Exhausted all quality levels
            if (tempOutput.exists()) {
                // Use last attempt anyway
                tempOutput.copyTo(finalOutput, overwrite = true)
                tempOutput.delete()
                inputFile.delete()
                send(
                    ConversionProgress.Complete(
                        outputPath = finalOutput.absolutePath,
                        fileSizeBytes = finalOutput.length(),
                        qualityUsed = quality + config.qualityStep,
                    )
                )
            } else {
                send(ConversionProgress.Failed("Could not meet target size at minimum quality"))
            }
        } catch (e: Exception) {
            send(ConversionProgress.Failed(e.message ?: "Unknown error"))
        } finally {
            tempOutput.delete()
            inputFile.delete()
        }

        close()
    }.flowOn(Dispatchers.IO)

    /**
     * Build the FFmpeg command matching the discwebp algorithm:
     * scale → fps → unsharp → libwebp with quality/compression/preset/loop
     */
    private fun buildFfmpegCommand(
        inputPath: String,
        outputPath: String,
        config: ConversionConfig,
        quality: Int,
    ): String {
        val filters = buildList {
            // Scale to max dimension, preserve aspect ratio, use Lanczos
            add("scale='min(${config.maxDimension},iw)':'min(${config.maxDimension},ih)':force_original_aspect_ratio=decrease:flags=lanczos")
            // Set frame rate
            add("fps=${config.fps}")
            // Optional sharpening for text clarity (matches discwebp unsharp params)
            if (config.sharpen) {
                add("unsharp=5:5:1.2:5:5:0.6")
            }
        }.joinToString(",")

        return buildString {
            append("-y ")                               // Overwrite output
            append("-i \"$inputPath\" ")                // Input file
            append("-vf \"$filters\" ")                 // Video filters
            append("-c:v libwebp_anim ")                // Animated WebP encoder
            append("-quality $quality ")                // WebP quality
            append("-compression_level ${config.compressionLevel} ")
            append("-preset picture ")                  // WebP preset for still-ish content
            append("-loop ${config.loop} ")             // Loop count (0=infinite)
            append("-an ")                              // Strip audio
            append("-vsync vfr ")                       // Variable frame rate
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
                if (cont.isActive) {
                    cont.resume(ReturnCode.isSuccess(returnCode))
                }
            },
            { /* log callback — ignored */ },
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
    data class Failed(val message: String) : ConversionProgress
}
