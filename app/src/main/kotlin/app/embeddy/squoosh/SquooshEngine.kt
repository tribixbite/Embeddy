package app.embeddy.squoosh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.core.graphics.scale
import app.embeddy.util.BitmapLoader
import app.embeddy.util.FileInfoUtils
import app.embeddy.util.deleteStaleEntries
import com.radzivon.bartoshyk.avif.coder.AvifSpeed
import com.radzivon.bartoshyk.avif.coder.HeifCoder
import com.radzivon.bartoshyk.avif.coder.PreciseMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil

/**
 * Image compression engine using Android's native Bitmap APIs.
 * Avoids ffmpeg dependency for still image compression — faster and lighter.
 *
 * Inspired by Google Squoosh and cleverkeys-gif-module compression profiles:
 * - Configurable output format (WebP, JPEG, PNG)
 * - Quality control for lossy formats
 * - Lossless WebP option
 * - Max dimension scaling with Lanczos-quality downsampling
 * - Exact width/height crop with center-crop
 * - AVIF support via awxkee/avif-coder (libaom-based, all API levels)
 */
class SquooshEngine(private val context: Context) {

    private val outputDir: File
        get() = File(context.cacheDir, "squoosh_out").also { it.mkdirs() }

    /** Compress an image with the given configuration. */
    suspend fun compress(uri: Uri, config: SquooshConfig): SquooshResult = withContext(Dispatchers.IO) {
        try {
            compressInternal(uri, config)
        } catch (e: OutOfMemoryError) {
            // Decoding is budgeted (see BitmapLoader.decodePixelBudget), but rotation/scaling
            // copies of an enormous image can still exhaust the heap. An Error
            // is not an Exception: without this the ViewModel's catch missed it
            // and the app crashed.
            throw SquooshException("Image is too large to process on this device", e)
        }
    }

    private fun compressInternal(uri: Uri, config: SquooshConfig): SquooshResult {
        val fileName = FileInfoUtils.queryFileName(context, uri) ?: "image"
        val baseName = FileInfoUtils.sanitizeFileName(
            fileName.substringBeforeLast("."),
            fallback = "image",
        )

        // Read original file size. Prefer the provider's SIZE column — InputStream.available()
        // is only an estimate of what can be read without blocking, so it under-reports for
        // non-file-backed providers and would skew savingsPercent.
        val originalSize = FileInfoUtils.queryFileSize(context, uri)?.takeIf { it > 0 }
            ?: context.contentResolver.openInputStream(uri)?.use { it.available().toLong() }
            ?: 0L

        // Decode with optional downsampling, then apply EXIF rotation so
        // the output matches what the user sees in their gallery app.
        val targetDim = when {
            config.exactWidth > 0 && config.exactHeight > 0 -> maxOf(config.exactWidth, config.exactHeight)
            config.exactWidth > 0 -> config.exactWidth
            config.exactHeight > 0 -> config.exactHeight
            else -> config.maxDimension
        }
        val decoded = BitmapLoader.load(context, uri, targetDim)
            ?: throw SquooshException("Failed to decode image")
        val bitmap = decoded.bitmap

        // Report the TRUE source dimensions from the bounds pass, not the
        // subsampled decode size (inSampleSize made a 4032x3024 photo claim
        // "original 1008x756"); BitmapLoader already swaps for EXIF rotation.
        val originalWidth = decoded.sourceWidth
        val originalHeight = decoded.sourceHeight
        Timber.d("Squoosh: %s (%dx%d, %d bytes) → %s q=%d",
            fileName, originalWidth, originalHeight, originalSize,
            config.format.label, config.quality)

        // Apply resizing: exact crop or max-dimension scaling
        val resized = when {
            // Exact width+height: scale to cover then center-crop
            config.exactWidth > 0 && config.exactHeight > 0 -> {
                centerCrop(bitmap, config.exactWidth, config.exactHeight)
            }
            // Exact width only: scale width, derive height from aspect ratio
            config.exactWidth > 0 -> {
                val scale = config.exactWidth.toFloat() / bitmap.width
                val newH = (bitmap.height * scale).toInt()
                bitmap.scale(config.exactWidth, newH).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            }
            // Exact height only: scale height, derive width from aspect ratio
            config.exactHeight > 0 -> {
                val scale = config.exactHeight.toFloat() / bitmap.height
                val newW = (bitmap.width * scale).toInt()
                bitmap.scale(newW, config.exactHeight).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            }
            // Max dimension scaling
            config.maxDimension > 0 &&
                (bitmap.width > config.maxDimension || bitmap.height > config.maxDimension) -> {
                val scale = minOf(
                    config.maxDimension.toFloat() / bitmap.width,
                    config.maxDimension.toFloat() / bitmap.height,
                )
                val newW = (bitmap.width * scale).toInt()
                val newH = (bitmap.height * scale).toInt()
                bitmap.scale(newW, newH).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            }
            else -> bitmap
        }

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

            return SquooshResult(
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
     * Scale bitmap to fill the target rect then center-crop to exact dimensions.
     * Produces an image that is exactly targetW x targetH without letterboxing.
     */
    private fun centerCrop(source: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val g = centerCropGeometry(source.width, source.height, targetW, targetH)
        val scaled = source.scale(g.scaledWidth, g.scaledHeight)
        if (scaled !== source) source.recycle()
        val cropped = Bitmap.createBitmap(scaled, g.cropX, g.cropY, targetW, targetH)
        if (cropped !== scaled) scaled.recycle()
        return cropped
    }





    /** Map our OutputFormat + config to the Android Bitmap.CompressFormat. */
    private fun resolveCompressFormat(config: SquooshConfig): Bitmap.CompressFormat {
        return when (config.format) {
            OutputFormat.WEBP -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
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


    /** Clean up old output files older than 24 hours. */
    fun cleanup() {
        deleteStaleEntries(outputDir, System.currentTimeMillis() - 24L * 60 * 60 * 1000)
    }

}

/**
 * BitmapFactory `inSampleSize` (a power of two) for decoding a [width]x[height]
 * image.
 *
 * Two rules: subsample while the image is more than twice [targetDim] on both
 * axes (quality-preserving downscale headroom), then keep halving until the
 * decode fits [maxPixels]. The second rule is what prevents the OOM: with
 * "no resize" (targetDim 0) a 200 MP photo used to decode at full size —
 * ~800 MB of ARGB — and crash the app.
 */
internal fun sampleSizeFor(width: Int, height: Int, targetDim: Int, maxPixels: Long): Int {
    if (width <= 0 || height <= 0) return 1
    val target = if (targetDim > 0) targetDim else maxOf(width, height)
    var sample = 1
    while (width / sample > target * 2 && height / sample > target * 2) sample *= 2
    while ((width / sample).toLong() * (height / sample) > maxPixels && sample < 1 shl 10) sample *= 2
    return sample
}

/** Scale-then-crop plan for an exact-size center crop. */
internal data class CropGeometry(val scaledWidth: Int, val scaledHeight: Int, val cropX: Int, val cropY: Int)

/**
 * Scale a [srcW]x[srcH] image to cover [targetW]x[targetH], then center-crop.
 * The scaled size rounds UP and is floored at the target, so truncation can
 * never leave the scaled bitmap a pixel smaller than the crop rect (which made
 * `Bitmap.createBitmap` throw).
 */
internal fun centerCropGeometry(srcW: Int, srcH: Int, targetW: Int, targetH: Int): CropGeometry {
    val srcAspect = srcW.toFloat() / srcH
    val dstAspect = targetW.toFloat() / targetH
    val (scaledW, scaledH) = if (srcAspect > dstAspect) {
        // Source is wider — fit height, crop width
        ceil(srcW * targetH.toDouble() / srcH).toInt().coerceAtLeast(targetW) to targetH
    } else {
        // Source is taller — fit width, crop height
        targetW to ceil(srcH * targetW.toDouble() / srcW).toInt().coerceAtLeast(targetH)
    }
    return CropGeometry(scaledW, scaledH, (scaledW - targetW) / 2, (scaledH - targetH) / 2)
}

/**
 * True source dimensions after applying an EXIF orientation: the four
 * 90°/270°-family orientations swap width and height. Pure and top-level so
 * the JVM unit suite can pin the swap logic without BitmapFactory.
 */
internal fun orientedDimensions(orientation: Int, width: Int, height: Int): Pair<Int, Int> =
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_TRANSVERSE,
        -> height to width
        else -> width to height
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
    val exactWidth: Int = 0,       // 0 = use maxDimension; >0 = exact output width
    val exactHeight: Int = 0,      // 0 = use maxDimension; >0 = exact output height
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
    data class Done(val result: SquooshResult, val inputFileName: String, val inputUri: String = "") : SquooshState
    data class Error(val message: String) : SquooshState
}
