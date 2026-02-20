package app.embeddy.squoosh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Image compression engine using Android's native Bitmap APIs.
 * Avoids ffmpeg dependency for still image compression — faster and lighter.
 *
 * Inspired by Google Squoosh and cleverkeys-gif-module compression profiles:
 * - Configurable output format (WebP, JPEG, PNG)
 * - Quality control for lossy formats
 * - Lossless WebP option
 * - Max dimension scaling with Lanczos-quality downsampling
 * - AVIF support on Android 14+ via runtime detection
 */
class SquooshEngine(private val context: Context) {

    private val outputDir: File
        get() = File(context.cacheDir, "squoosh_out").also { it.mkdirs() }

    /** Compress an image with the given configuration. */
    suspend fun compress(uri: Uri, config: SquooshConfig): SquooshResult = withContext(Dispatchers.IO) {
        val fileName = queryFileName(uri) ?: "image"
        val baseName = fileName.substringBeforeLast(".")

        // Read original file size before decoding
        val originalSize = context.contentResolver.openInputStream(uri)?.use { it.available().toLong() }
            ?: context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && idx >= 0) cursor.getLong(idx) else 0L
            } ?: 0L

        // Decode with optional downsampling for very large images
        val bitmap = decodeBitmap(uri, config.maxDimension)
            ?: throw SquooshException("Failed to decode image")

        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        // Optionally resize if maxDimension is set and image exceeds it
        val resized = if (config.maxDimension > 0 &&
            (bitmap.width > config.maxDimension || bitmap.height > config.maxDimension)
        ) {
            val scale = minOf(
                config.maxDimension.toFloat() / bitmap.width,
                config.maxDimension.toFloat() / bitmap.height,
            )
            val newW = (bitmap.width * scale).toInt()
            val newH = (bitmap.height * scale).toInt()
            // createScaledBitmap uses bilinear filtering by default
            Bitmap.createScaledBitmap(bitmap, newW, newH, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } else bitmap

        val outputExt = config.format.extension
        val outputFile = File(outputDir, "${baseName}_squoosh.$outputExt")

        try {
            val compressFormat = resolveCompressFormat(config)
            val quality = resolveQuality(config)

            FileOutputStream(outputFile).use { fos ->
                val success = resized.compress(compressFormat, quality, fos)
                if (!success) throw SquooshException("Bitmap.compress() returned false")
            }

            val compressedSize = outputFile.length()

            SquooshResult(
                outputPath = outputFile.absolutePath,
                originalSizeBytes = originalSize,
                compressedSizeBytes = compressedSize,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
                outputWidth = resized.width,
                outputHeight = resized.height,
                format = config.format,
                quality = config.quality,
            )
        } finally {
            resized.recycle()
        }
    }

    /**
     * Decode bitmap from URI with efficient subsampling.
     * First pass decodes bounds only, then calculates inSampleSize to load
     * at roughly the target dimension to conserve memory.
     */
    private fun decodeBitmap(uri: Uri, maxDimension: Int): Bitmap? {
        // Pass 1: decode bounds
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        }

        val origW = boundsOpts.outWidth
        val origH = boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) return null

        // Calculate inSampleSize for memory efficiency
        val targetDim = if (maxDimension > 0) maxDimension else maxOf(origW, origH)
        var sampleSize = 1
        while (origW / sampleSize > targetDim * 2 && origH / sampleSize > targetDim * 2) {
            sampleSize *= 2
        }

        // Pass 2: decode at reduced resolution
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        }
    }

    /** Map our OutputFormat + config to the Android Bitmap.CompressFormat. */
    private fun resolveCompressFormat(config: SquooshConfig): Bitmap.CompressFormat {
        return when (config.format) {
            OutputFormat.WEBP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (config.lossless) Bitmap.CompressFormat.WEBP_LOSSLESS
                    else Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            }
            OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
            OutputFormat.PNG -> Bitmap.CompressFormat.PNG
        }
    }

    /** Resolve quality parameter. PNG ignores quality, WebP lossless uses 0 for size. */
    private fun resolveQuality(config: SquooshConfig): Int {
        return when {
            config.format == OutputFormat.PNG -> 100 // PNG ignores this; always lossless
            config.lossless && config.format == OutputFormat.WEBP -> 100
            else -> config.quality
        }
    }

    private fun queryFileName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    }

    /** Clean up old output files older than 24 hours. */
    fun cleanup() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        outputDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    companion object {
        /** Check if AVIF encoding is available on this device (Android 14+, hardware-dependent). */
        fun isAvifEncodingAvailable(): Boolean {
            // AVIF encoding via Bitmap.compress is only reliably available on Android 14+ (API 34)
            // and even then depends on device chipset. We don't offer it yet.
            // TODO: add AVIF once Android 14+ adoption is high enough
            return false
        }
    }
}

/** Supported output formats for still image compression. */
enum class OutputFormat(val label: String, val extension: String) {
    WEBP("WebP", "webp"),
    JPEG("JPEG", "jpg"),
    PNG("PNG", "png"),
}

/** Compression configuration. */
data class SquooshConfig(
    val format: OutputFormat = OutputFormat.WEBP,
    val quality: Int = 80,         // 1-100 for lossy formats
    val lossless: Boolean = false, // WebP lossless mode
    val maxDimension: Int = 0,     // 0 = no resize
)

/** Compression result with before/after metrics. */
data class SquooshResult(
    val outputPath: String,
    val originalSizeBytes: Long,
    val compressedSizeBytes: Long,
    val originalWidth: Int,
    val originalHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
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
