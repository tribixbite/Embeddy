package app.embeddy.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import app.embeddy.squoosh.orientedDimensions
import app.embeddy.squoosh.sampleSizeFor

/**
 * Memory-budgeted still-image decoding with EXIF orientation applied — shared
 * by Squoosh compression and the Convert tab's image-sequence renderer.
 */
object BitmapLoader {

    /** A decoded (possibly subsampled) bitmap plus the TRUE oriented source size. */
    data class Decoded(
        /** Upright bitmap (EXIF rotation/flip applied). */
        val bitmap: Bitmap,
        /** Full-resolution width after orientation, before subsampling. */
        val sourceWidth: Int,
        /** Full-resolution height after orientation, before subsampling. */
        val sourceHeight: Int,
    )

    /**
     * Decode [uri] at roughly [targetDimension] (0 = full size, still capped by
     * the heap budget) and rotate it upright. Returns null when the image can't
     * be decoded.
     */
    fun load(context: Context, uri: Uri, targetDimension: Int): Decoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(width, height, targetDimension, decodePixelBudget())
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        val orientation = readExifOrientation(context, uri)
        val (orientedW, orientedH) = orientedDimensions(orientation, width, height)
        return Decoded(rotateForExif(raw, orientation), orientedW, orientedH)
    }

    /**
     * Pixels one decoded ARGB bitmap may occupy: an eighth of the heap, since
     * EXIF rotation and scaling briefly hold a second and third copy.
     */
    fun decodePixelBudget(): Long = Runtime.getRuntime().maxMemory() / 8 / BYTES_PER_PIXEL

    /** Read the EXIF orientation tag, defaulting to NORMAL on any failure. */
    private fun readExifOrientation(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * Rotate/flip the bitmap to match its EXIF orientation. BitmapFactory
     * ignores EXIF orientation, so phone photos would otherwise come out sideways.
     */
    private fun rotateForExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private const val BYTES_PER_PIXEL = 4L
}
