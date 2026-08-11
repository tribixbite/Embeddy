package app.embeddy.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Paint
import android.graphics.Rect
import timber.log.Timber
import java.io.File
import java.util.Locale

/**
 * Turns an animated WebP into a PNG sequence plus an FFmpeg concat script.
 *
 * FFmpeg 6.0 (the bundled ffmpeg-kit build) cannot demux animated WebP, so the
 * frames are decoded here — [WebPFrameSplitter] re-wraps each ANMF chunk as a
 * still image and `BitmapFactory` decodes it — then composited and written out
 * for FFmpeg to re-encode with the normal filter chain.
 *
 * A concat script is used rather than `-framerate` + image2 so per-frame
 * durations survive; animated WebP allows a different delay on every frame.
 */
class AnimatedWebPExtractor(private val workDir: File) {

    data class Extracted(
        /** FFmpeg concat demuxer script listing each frame and its duration */
        val concatScript: File,
        val width: Int,
        val height: Int,
        val frameCount: Int,
        val totalDurationMs: Int,
        val averageFps: Double,
        /** True when the frame cap stopped extraction before the end */
        val truncated: Boolean,
    )

    /**
     * Decode [source] and write its frames as PNGs under [workDir].
     * Returns null when the input is not an animated WebP.
     *
     * @param maxFrames safety cap on how many frames are written to disk
     */
    fun extract(source: ByteArray, maxFrames: Int = MAX_FRAMES): Extracted? {
        val frameSet = WebPFrameSplitter.split(source) ?: return null

        val canvas = Bitmap.createBitmap(
            frameSet.width, frameSet.height, Bitmap.Config.ARGB_8888,
        )
        val canvasDraw = Canvas(canvas)
        // Frames marked "replace" overwrite their region rather than blending,
        // which needs SRC rather than the default SRC_OVER.
        val replacePaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }

        val limit = minOf(frameSet.frames.size, maxFrames)
        val lines = StringBuilder()
        var written = 0
        var totalDurationMs = 0

        try {
            for (index in 0 until limit) {
                val frame = frameSet.frames[index]

                val bitmap = BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size)
                if (bitmap == null) {
                    Timber.w("Frame %d failed to decode, skipping", index)
                    continue
                }

                val dest = Rect(frame.x, frame.y, frame.x + frame.width, frame.y + frame.height)
                if (frame.blend == WebPFrameSplitter.Blend.REPLACE) {
                    canvasDraw.drawBitmap(bitmap, null, dest, replacePaint)
                } else {
                    canvasDraw.drawBitmap(bitmap, null, dest, null)
                }
                bitmap.recycle()

                val outFile = File(workDir, String.format(Locale.US, "frame_%05d.png", written))
                outFile.outputStream().use { out ->
                    canvas.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                // A zero duration means "as fast as possible"; clamp so the concat
                // script never emits a 0s entry, which FFmpeg treats as undefined.
                val durationMs = frame.durationMs.coerceAtLeast(MIN_FRAME_DURATION_MS)
                totalDurationMs += durationMs
                lines.append("file '").append(outFile.name).append("'\n")
                lines.append(
                    String.format(Locale.US, "duration %.3f%n", durationMs / 1000.0)
                )
                written++

                if (frame.dispose == WebPFrameSplitter.Dispose.BACKGROUND) {
                    canvasDraw.drawRect(dest, CLEAR_PAINT)
                }
            }
        } finally {
            canvas.recycle()
        }

        if (written == 0) return null

        // The concat demuxer ignores the final entry's duration unless the last
        // file is repeated, so re-list it to give the closing frame its time.
        lines.append(
            String.format(Locale.US, "file 'frame_%05d.png'%n", written - 1)
        )

        val script = File(workDir, "frames.txt").apply { writeText(lines.toString()) }
        Timber.d(
            "Extracted %d animated WebP frames (%dx%d, %dms total)",
            written, frameSet.width, frameSet.height, totalDurationMs,
        )

        return Extracted(
            concatScript = script,
            width = frameSet.width,
            height = frameSet.height,
            frameCount = written,
            totalDurationMs = totalDurationMs,
            averageFps = if (totalDurationMs > 0 && written > 1) {
                written * 1000.0 / totalDurationMs
            } else 0.0,
            truncated = limit < frameSet.frames.size,
        )
    }

    companion object {
        /** Bounds temp-directory usage; matches the web decoder's cap. */
        const val MAX_FRAMES = 1500

        /** WebP allows 0ms delays; FFmpeg needs a real duration. */
        private const val MIN_FRAME_DURATION_MS = 20

        private val CLEAR_PAINT = Paint().apply {
            color = Color.TRANSPARENT
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
    }
}
