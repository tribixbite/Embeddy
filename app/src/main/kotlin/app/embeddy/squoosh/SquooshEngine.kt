package app.embeddy.squoosh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Image compression engine inspired by Google Squoosh and cleverkeys-gif-module.
 * Uses ffmpeg-kit for codec control (WebP quality/effort, JPEG quality) and
 * Android's BitmapFactory for decoding with optional downscaling.
 */
class SquooshEngine(private val context: Context) {

    private val tempDir: File
        get() = File(context.cacheDir, "squoosh_temp").also { it.mkdirs() }

    private val outputDir: File
        get() = File(context.cacheDir, "squoosh_out").also { it.mkdirs() }

    /** Compress an image with the given configuration. */
    suspend fun compress(uri: Uri, config: SquooshConfig): SquooshResult = withContext(Dispatchers.IO) {
        val fileName = queryFileName(uri) ?: "image"
        val baseName = fileName.substringBeforeLast(".")

        // Copy input to temp
        val inputFile = copyToTemp(uri, fileName)
        val originalSize = inputFile.length()

        // Probe dimensions
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(inputFile.absolutePath, options)
        val originalWidth = options.outWidth
        val originalHeight = options.outHeight

        val outputExt = config.format.extension
        val outputFile = File(outputDir, "${baseName}_squoosh.$outputExt")

        try {
            val command = buildFfmpegCommand(inputFile, outputFile, config, originalWidth, originalHeight)
            val success = executeFfmpeg(command)

            if (!success || !outputFile.exists()) {
                inputFile.delete()
                throw SquooshException("Compression failed — FFmpeg returned an error")
            }

            val compressedSize = outputFile.length()
            inputFile.delete()

            SquooshResult(
                outputPath = outputFile.absolutePath,
                originalSizeBytes = originalSize,
                compressedSizeBytes = compressedSize,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
                format = config.format,
                quality = config.quality,
            )
        } catch (e: SquooshException) {
            throw e
        } catch (e: Exception) {
            inputFile.delete()
            throw SquooshException("Compression failed: ${e.message}", e)
        }
    }

    /** Build FFmpeg command based on output format and config. */
    private fun buildFfmpegCommand(
        input: File,
        output: File,
        config: SquooshConfig,
        origWidth: Int,
        origHeight: Int,
    ): String {
        val filters = buildList {
            // Scale if maxDimension is set and image exceeds it
            if (config.maxDimension > 0 && (origWidth > config.maxDimension || origHeight > config.maxDimension)) {
                add("scale='min(${config.maxDimension},iw)':'min(${config.maxDimension},ih)':force_original_aspect_ratio=decrease:flags=lanczos")
            }
        }

        val filterArg = if (filters.isNotEmpty()) "-vf \"${filters.joinToString(",")}\" " else ""

        return buildString {
            append("-y -i \"${input.absolutePath}\" ")
            append(filterArg)

            when (config.format) {
                OutputFormat.WEBP -> {
                    if (config.lossless) {
                        append("-c:v libwebp -lossless 1 ")
                        append("-compression_level ${config.effort} ")
                    } else {
                        append("-c:v libwebp -lossless 0 ")
                        append("-quality ${config.quality} ")
                        append("-compression_level ${config.effort} ")
                    }
                }

                OutputFormat.JPEG -> {
                    // ffmpeg q:v scale: 2 (best) to 31 (worst)
                    // Map our 1-100 quality to 31-2 scale
                    val qv = (31 - (config.quality / 100.0 * 29)).toInt().coerceIn(2, 31)
                    append("-q:v $qv ")
                }

                OutputFormat.PNG -> {
                    // PNG is always lossless; compression_level 0-100 controls speed vs size
                    append("-compression_level ${config.effort * 15} ")
                }
            }

            append("-frames:v 1 ")  // Single frame (still image)
            append("\"${output.absolutePath}\"")
        }
    }

    /** Execute an FFmpeg command, returns true on success. */
    private suspend fun executeFfmpeg(command: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeAsync(command) { session ->
                if (cont.isActive) {
                    cont.resume(ReturnCode.isSuccess(session.returnCode))
                }
            }
            cont.invokeOnCancellation { session.cancel() }
        }

    private fun copyToTemp(uri: Uri, fileName: String): File {
        val tempFile = File(tempDir, "sq_${System.currentTimeMillis()}_$fileName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw SquooshException("Cannot read file")
        return tempFile
    }

    private fun queryFileName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    }

    /** Clean up old output files. */
    fun cleanup() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        outputDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        tempDir.listFiles()?.forEach { it.delete() }
    }
}

/** Supported output formats. */
enum class OutputFormat(val label: String, val extension: String) {
    WEBP("WebP", "webp"),
    JPEG("JPEG", "jpg"),
    PNG("PNG", "png"),
}

/** Compression configuration. */
data class SquooshConfig(
    val format: OutputFormat = OutputFormat.WEBP,
    val quality: Int = 80,         // 1-100, higher = better quality / larger file
    val effort: Int = 4,           // 0-6 for WebP compression_level
    val lossless: Boolean = false,
    val maxDimension: Int = 0,     // 0 = no resize
)

/** Compression result. */
data class SquooshResult(
    val outputPath: String,
    val originalSizeBytes: Long,
    val compressedSizeBytes: Long,
    val originalWidth: Int,
    val originalHeight: Int,
    val format: OutputFormat,
    val quality: Int,
) {
    val savingsPercent: Float
        get() = if (originalSizeBytes > 0) {
            ((originalSizeBytes - compressedSizeBytes).toFloat() / originalSizeBytes * 100)
        } else 0f
}

class SquooshException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Sealed interface for squoosh screen state. */
sealed interface SquooshState {
    data object Idle : SquooshState
    data class Ready(val fileName: String, val fileSize: Long, val uri: String) : SquooshState
    data class Compressing(val fileName: String) : SquooshState
    data class Done(val result: SquooshResult, val inputFileName: String) : SquooshState
    data class Error(val message: String) : SquooshState
}
