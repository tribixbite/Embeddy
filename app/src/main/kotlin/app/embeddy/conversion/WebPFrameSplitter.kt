package app.embeddy.conversion

/**
 * Splits an animated WebP into standalone still-image WebPs, one per frame.
 *
 * The bundled ffmpeg-kit build (FFmpeg 6.0, upstream archived) cannot demux
 * animated WebP, so FFmpeg alone can only ever see the first frame. An animated
 * WebP is however just a RIFF container of ANMF chunks, each holding an ordinary
 * VP8/VP8L bitstream — re-wrapping one in a minimal `RIFF….WEBP` header yields a
 * still image `BitmapFactory` decodes on every supported API level.
 *
 * Deliberately free of Android dependencies so the container logic is unit-testable.
 *
 * Container reference: https://developers.google.com/speed/webp/docs/riff_container
 */
object WebPFrameSplitter {

    /** How a frame combines with the canvas beneath it. */
    enum class Blend { BLEND, REPLACE }

    /** What happens to the frame's region before the next frame draws. */
    enum class Dispose { NONE, BACKGROUND }

    /** One animation frame, re-wrapped as an independently decodable WebP. */
    data class Frame(
        /** A complete still WebP file containing just this frame's pixels */
        val bytes: ByteArray,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        /** Display duration in milliseconds */
        val durationMs: Int,
        val blend: Blend,
        val dispose: Dispose,
    ) {
        // ByteArray breaks data-class equality; compare by content instead.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return bytes.contentEquals(other.bytes) && x == other.x && y == other.y &&
                width == other.width && height == other.height &&
                durationMs == other.durationMs && blend == other.blend && dispose == other.dispose
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + x
            result = 31 * result + y
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + durationMs
            result = 31 * result + blend.hashCode()
            result = 31 * result + dispose.hashCode()
            return result
        }
    }

    /** Result of splitting an animated WebP container. */
    data class FrameSet(
        val width: Int,
        val height: Int,
        /** 0 = loop forever */
        val loopCount: Int,
        val frames: List<Frame>,
    ) {
        val totalDurationMs: Int get() = frames.sumOf { it.durationMs }
        /** Average frame rate across the animation, or 0 when it can't be derived. */
        val averageFps: Double
            get() = if (totalDurationMs > 0 && frames.size > 1) {
                frames.size * 1000.0 / totalDurationMs
            } else 0.0
    }

    /** Cheap check that avoids a full parse when the file clearly isn't animated. */
    fun isAnimatedWebP(bytes: ByteArray): Boolean {
        if (bytes.size < 21) return false
        if (fourCC(bytes, 0) != "RIFF" || fourCC(bytes, 8) != "WEBP") return false
        if (fourCC(bytes, 12) != "VP8X") return false
        return (bytes[20].toInt() and 0x02) != 0
    }

    /**
     * Parse an animated WebP and return each frame as a standalone still WebP.
     * Returns null when the input is not an animated WebP or carries no frames.
     */
    fun split(bytes: ByteArray): FrameSet? {
        val size = bytes.size
        if (size < 16) return null
        if (fourCC(bytes, 0) != "RIFF" || fourCC(bytes, 8) != "WEBP") return null

        var canvasWidth = 0
        var canvasHeight = 0
        var loopCount = 0
        var isAnimated = false
        val frames = mutableListOf<Frame>()

        var offset = 12
        while (offset + 8 <= size) {
            val id = fourCC(bytes, offset)
            val declaredSize = readUInt32LE(bytes, offset + 4)
            val dataStart = offset + 8
            // Clamp: a truncated or hostile file can declare more than it holds
            val chunkSize = minOf(declaredSize, (size - dataStart).toLong()).toInt()
            val next = dataStart + declaredSize + (declaredSize % 2)

            when {
                id == "VP8X" && chunkSize >= 10 -> {
                    isAnimated = (bytes[dataStart].toInt() and 0x02) != 0
                    canvasWidth = readUInt24LE(bytes, dataStart + 4) + 1
                    canvasHeight = readUInt24LE(bytes, dataStart + 7) + 1
                }

                id == "ANIM" && chunkSize >= 6 -> {
                    loopCount = readUInt16LE(bytes, dataStart + 4)
                }

                id == "ANMF" && chunkSize >= 16 -> {
                    parseFrame(bytes, dataStart, chunkSize)?.let(frames::add)
                }
            }

            // Guard against a malformed size that fails to advance the cursor
            if (next <= offset || next > Int.MAX_VALUE.toLong()) break
            offset = next.toInt()
        }

        if (!isAnimated || frames.isEmpty()) return null
        if (canvasWidth <= 0 || canvasHeight <= 0) return null
        return FrameSet(canvasWidth, canvasHeight, loopCount, frames)
    }

    /** Read one ANMF chunk into a Frame, or null when it carries no image data. */
    private fun parseFrame(bytes: ByteArray, dataStart: Int, chunkSize: Int): Frame? {
        // Header: x/2, y/2, w-1, h-1 (3 bytes each), duration (3), flags (1)
        val x = readUInt24LE(bytes, dataStart) * 2
        val y = readUInt24LE(bytes, dataStart + 3) * 2
        val width = readUInt24LE(bytes, dataStart + 6) + 1
        val height = readUInt24LE(bytes, dataStart + 9) + 1
        val duration = readUInt24LE(bytes, dataStart + 12)
        val flags = bytes[dataStart + 15].toInt()

        // Spec bit layout: 6 reserved bits, then blending (B), then disposal (D)
        val blend = if ((flags shr 1) and 1 == 1) Blend.REPLACE else Blend.BLEND
        val dispose = if (flags and 1 == 1) Dispose.BACKGROUND else Dispose.NONE

        // Collect the frame's own image sub-chunks (ALPH and/or VP8 /VP8L).
        // Tracked as explicit offset+length rather than an IntRange: `until`
        // yields an inclusive end, and treating `last` as exclusive silently
        // truncates every chunk by a byte, corrupting the bitstream.
        val imageChunks = mutableListOf<SubChunk>()
        var hasAlpha = false
        val frameEnd = dataStart + chunkSize
        var sub = dataStart + 16
        while (sub + 8 <= frameEnd) {
            val subId = fourCC(bytes, sub)
            val subSize = readUInt32LE(bytes, sub + 4)
            val padded = subSize + (subSize % 2)
            if (sub + 8 + padded > frameEnd) break
            if (subId == "ALPH" || subId == "VP8 " || subId == "VP8L") {
                imageChunks.add(SubChunk(start = sub, length = (8 + padded).toInt()))
                if (subId == "ALPH") hasAlpha = true
            }
            sub += (8 + padded).toInt()
        }

        if (imageChunks.isEmpty()) return null

        return Frame(
            bytes = buildStillWebP(bytes, imageChunks, hasAlpha, width, height),
            x = x,
            y = y,
            width = width,
            height = height,
            durationMs = duration,
            blend = blend,
            dispose = dispose,
        )
    }

    /**
     * Wrap a frame's image sub-chunks in a minimal still-image WebP container.
     *
     * A bare VP8/VP8L chunk suffices on its own. A separate ALPH chunk is only
     * meaningful alongside a VP8X header, so one is synthesised with the alpha
     * flag set and the frame's own dimensions as the canvas.
     */
    private fun buildStillWebP(
        source: ByteArray,
        imageChunks: List<SubChunk>,
        hasAlpha: Boolean,
        width: Int,
        height: Int,
    ): ByteArray {
        val vp8xSize = if (hasAlpha) 8 + 10 else 0
        val payloadSize = imageChunks.sumOf { it.length }
        val riffPayload = 4 + vp8xSize + payloadSize // "WEBP" + optional VP8X + chunks
        val out = ByteArray(8 + riffPayload)

        writeFourCC(out, 0, "RIFF")
        writeUInt32LE(out, 4, riffPayload)
        writeFourCC(out, 8, "WEBP")

        var cursor = 12
        if (hasAlpha) {
            writeFourCC(out, cursor, "VP8X")
            writeUInt32LE(out, cursor + 4, 10)
            out[cursor + 8] = 0x10 // alpha flag; bytes 9-11 reserved and already zero
            writeUInt24LE(out, cursor + 12, width - 1)
            writeUInt24LE(out, cursor + 15, height - 1)
            cursor += 18
        }

        for (chunk in imageChunks) {
            System.arraycopy(source, chunk.start, out, cursor, chunk.length)
            cursor += chunk.length
        }
        return out
    }

    /** A chunk located inside a frame payload: header offset and total byte length. */
    private data class SubChunk(val start: Int, val length: Int)

    // ── Byte helpers ────────────────────────────────────────────────────────

    private fun fourCC(bytes: ByteArray, offset: Int): String {
        if (offset + 4 > bytes.size) return ""
        return String(
            charArrayOf(
                (bytes[offset].toInt() and 0xff).toChar(),
                (bytes[offset + 1].toInt() and 0xff).toChar(),
                (bytes[offset + 2].toInt() and 0xff).toChar(),
                (bytes[offset + 3].toInt() and 0xff).toChar(),
            )
        )
    }

    /** Returned as Long because a uint32 can exceed Int.MAX_VALUE. */
    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun readUInt24LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16)

    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun writeFourCC(out: ByteArray, offset: Int, id: String) {
        for (i in 0 until 4) out[offset + i] = id[i].code.toByte()
    }

    private fun writeUInt32LE(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xff).toByte()
        out[offset + 1] = ((value shr 8) and 0xff).toByte()
        out[offset + 2] = ((value shr 16) and 0xff).toByte()
        out[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun writeUInt24LE(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xff).toByte()
        out[offset + 1] = ((value shr 8) and 0xff).toByte()
        out[offset + 2] = ((value shr 16) and 0xff).toByte()
    }
}
