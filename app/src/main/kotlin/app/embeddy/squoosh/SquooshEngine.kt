package app.embeddy.squoosh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.radzivon.bartoshyk.avif.coder.AvifSpeed
import com.radzivon.bartoshyk.avif.coder.HeifCoder
import com.radzivon.bartoshyk.avif.coder.PreciseMode
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
 * - AVIF support via awxkee/avif-coder (libaom-based, all API levels)
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

        // Decode with optional downsampling, then apply EXIF rotation so
        // the output matches what the user sees in their gallery app.
        val rawBitmap = decodeBitmap(uri, config.maxDimension)
            ?: throw SquooshException("Failed to decode image")
        val bitmap = applyExifRotation(uri, rawBitmap)

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
            if (config.format == OutputFormat.AVIF) {
                // AVIF encoding via libaom through avif-coder JNI bindings.
                // Speed SIX is the Squoosh.app-equivalent sweet spot for mobile:
                // reasonable encode time without sacrificing compression density.
                val preciseMode = if (config.lossless) PreciseMode.LOSSLESS else PreciseMode.LOSSY
                val encoded = HeifCoder().encodeAvif(
                    bitmap = resized,
                    quality = config.quality,
                    speed = AvifSpeed.SIX,
                    preciseMode = preciseMode,
                )
                FileOutputStream(outputFile).use { fos -> fos.write(encoded) }
            } else {
                // Native Android compression for WebP, JPEG, PNG
                val compressFormat = resolveCompressFormat(config)
                val quality = resolveQuality(config)
                FileOutputStream(outputFile).use { fos ->
                    val success = resized.compress(compressFormat, quality, fos)
                    if (!success) throw SquooshException("Bitmap.compress() returned false")
                }
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

    /**
     * Read EXIF orientation from the source URI and rotate/flip the bitmap to match.
     * BitmapFactory ignores EXIF orientation — without this, photos from cameras
     * that store rotation in EXIF (most phones) appear sideways or upside-down.
     */
    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap // No rotation needed
        }

        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
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
            OutputFormat.AVIF -> throw IllegalStateException("AVIF uses HeifCoder, not Bitmap.compress")
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
        /** AVIF encoding is always available via bundled libaom native library. */
        fun isAvifEncodingAvailable(): Boolean = true
    }
}

/** Supported output formats for still image compression. */
enum class OutputFormat(val label: String, val extension: String, val mimeType: String) {
    WEBP("WebP", "webp", "image/webp"),
    JPEG("JPEG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
    AVIF("AVIF", "avif", "image/avif"),
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
