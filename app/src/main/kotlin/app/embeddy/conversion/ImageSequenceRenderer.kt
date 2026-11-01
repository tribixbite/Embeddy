package app.embeddy.conversion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.core.graphics.createBitmap
import app.embeddy.util.BitmapLoader
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Renders picked still images into equally sized PNG frames plus a concat
 * script, so an image sequence goes through the same FFmpeg pipeline as a
 * pre-decoded animated WebP.
 *
 * Images are decoded with Android's decoders (HEIC, EXIF orientation and
 * every format the platform supports — FFmpeg handles none of HEIC) and
 * letterboxed onto one canvas so the encoder sees a constant frame size.
 */
class ImageSequenceRenderer(private val context: Context, private val workDir: File) {

    /** The rendered sequence. */
    data class Rendered(val concatScript: File, val width: Int, val height: Int, val durationMs: Long)

    /**
     * Render [uris] in order, each shown for [frameDurationMs].
     * Unreadable images are skipped; throws when none can be decoded.
     */
    suspend fun render(uris: List<Uri>, frameDurationMs: Int): Rendered {
        require(uris.isNotEmpty()) { "No images to render" }
        val first = BitmapLoader.load(context, uris.first(), MAX_CANVAS_EDGE)
            ?: throw IllegalArgumentException("The first image could not be decoded")
        val (canvasW, canvasH) = sequenceCanvas(first.bitmap.width, first.bitmap.height)
        val canvasBitmap = createBitmap(canvasW, canvasH)
        val canvas = Canvas(canvasBitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val entries = mutableListOf<ConcatScript.Entry>()
        try {
            uris.forEachIndexed { index, uri ->
                coroutineContext.ensureActive()
                val decoded = if (index == 0) first else BitmapLoader.load(context, uri, MAX_CANVAS_EDGE)
                if (decoded == null) {
                    Timber.w("Image %d could not be decoded, skipping", index)
                    return@forEachIndexed
                }
                val image = decoded.bitmap
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(image, null, fitCenter(image.width, image.height, canvasW, canvasH), paint)
                image.recycle()
                val file = File(workDir, String.format(Locale.US, "image_%05d.png", entries.size))
                file.outputStream().use { canvasBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                entries += ConcatScript.Entry(file.name, frameDurationMs)
            }
        } finally {
            canvasBitmap.recycle()
        }
        require(entries.isNotEmpty()) { "None of the images could be decoded" }
        val script = File(workDir, "images.txt").apply { writeText(ConcatScript.build(entries)) }
        val total = entries.sumOf { ConcatScript.clampDuration(it.durationMs).toLong() }
        return Rendered(script, canvasW, canvasH, total)
    }

    private fun fitCenter(w: Int, h: Int, cw: Int, ch: Int): RectF {
        val scale = minOf(cw.toFloat() / w, ch.toFloat() / h)
        val dw = w * scale
        val dh = h * scale
        return RectF((cw - dw) / 2, (ch - dh) / 2, (cw + dw) / 2, (ch + dh) / 2)
    }

    companion object {
        /** Longest canvas edge: the encoder scales down further per settings anyway. */
        const val MAX_CANVAS_EDGE = 1920
    }
}

/**
 * Canvas size for an image sequence whose first image is [width]x[height]:
 * the same aspect, longest edge at most [ImageSequenceRenderer.MAX_CANVAS_EDGE],
 * both sides even.
 */
internal fun sequenceCanvas(width: Int, height: Int): Pair<Int, Int> {
    val longest = maxOf(width, height)
    val scale = if (longest > ImageSequenceRenderer.MAX_CANVAS_EDGE) {
        ImageSequenceRenderer.MAX_CANVAS_EDGE.toDouble() / longest
    } else 1.0
    return evenFloor((width * scale).toInt()) to evenFloor((height * scale).toInt())
}
