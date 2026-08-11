package app.embeddy.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the animated-WebP container splitter.
 *
 * These pin down the RIFF/ANMF layout the extractor depends on: the bundled
 * FFmpeg cannot demux animated WebP, so a parsing mistake here silently turns a
 * multi-frame conversion into a one-frame one.
 */
class WebPFrameSplitterTest {

    // ── Fixture builders ────────────────────────────────────────────────────

    private fun fourCC(id: String): List<Byte> = id.map { it.code.toByte() }

    private fun u32(n: Int): List<Byte> = listOf(
        (n and 0xff).toByte(),
        ((n shr 8) and 0xff).toByte(),
        ((n shr 16) and 0xff).toByte(),
        ((n ushr 24) and 0xff).toByte(),
    )

    private fun u24(n: Int): List<Byte> = listOf(
        (n and 0xff).toByte(),
        ((n shr 8) and 0xff).toByte(),
        ((n shr 16) and 0xff).toByte(),
    )

    private fun chunk(id: String, payload: List<Byte>): List<Byte> {
        val out = fourCC(id) + u32(payload.size) + payload
        return if (payload.size % 2 == 1) out + listOf(0.toByte()) else out
    }

    private fun riff(body: List<Byte>): ByteArray {
        val payload = fourCC("WEBP") + body
        return (fourCC("RIFF") + u32(payload.size) + payload).toByteArray()
    }

    private fun vp8x(flags: Int, w: Int, h: Int): List<Byte> =
        chunk("VP8X", listOf(flags.toByte(), 0, 0, 0) + u24(w - 1) + u24(h - 1))

    private fun anmf(
        w: Int,
        h: Int,
        durationMs: Int,
        x: Int = 0,
        y: Int = 0,
        flags: Int = 0,
        subs: List<Pair<String, List<Byte>>> = listOf("VP8 " to listOf<Byte>(1, 2, 3, 4)),
    ): List<Byte> {
        val body = u24(x / 2) + u24(y / 2) + u24(w - 1) + u24(h - 1) + u24(durationMs) +
            listOf(flags.toByte()) + subs.flatMap { chunk(it.first, it.second) }
        return chunk("ANMF", body)
    }

    private fun fourCCAt(bytes: ByteArray, offset: Int): String =
        String(bytes.copyOfRange(offset, offset + 4).map { (it.toInt() and 0xff).toChar() }
            .toCharArray())

    private fun readU32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    // ── isAnimatedWebP ──────────────────────────────────────────────────────

    @Test
    fun `isAnimatedWebP rejects non-WebP data`() {
        assertFalse(WebPFrameSplitter.isAnimatedWebP(ByteArray(64)))
        assertFalse(WebPFrameSplitter.isAnimatedWebP(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `isAnimatedWebP rejects a still WebP`() {
        val still = riff(vp8x(0x10, 32, 32) + chunk("VP8 ", listOf<Byte>(9, 9, 9, 9)))
        assertFalse(WebPFrameSplitter.isAnimatedWebP(still))
    }

    @Test
    fun `isAnimatedWebP accepts a container with the animation flag`() {
        val animated = riff(vp8x(0x02, 32, 32) + anmf(32, 32, 100))
        assertTrue(WebPFrameSplitter.isAnimatedWebP(animated))
    }

    @Test
    fun `isAnimatedWebP only needs the header, not the whole file`() {
        val animated = riff(vp8x(0x02, 32, 32) + anmf(32, 32, 100))
        val headerOnly = animated.copyOfRange(0, 21)
        assertTrue(WebPFrameSplitter.isAnimatedWebP(headerOnly))
    }

    // ── split: rejection cases ──────────────────────────────────────────────

    @Test
    fun `split returns null for a still WebP`() {
        val still = riff(vp8x(0x10, 32, 32) + chunk("VP8 ", listOf<Byte>(9, 9, 9, 9)))
        assertNull(WebPFrameSplitter.split(still))
    }

    @Test
    fun `split returns null when the animation flag is set but no frames follow`() {
        assertNull(WebPFrameSplitter.split(riff(vp8x(0x02, 32, 32))))
    }

    @Test
    fun `split returns null for truncated input`() {
        assertNull(WebPFrameSplitter.split(byteArrayOf(0x52, 0x49, 0x46, 0x46)))
    }

    // ── split: geometry and timing ──────────────────────────────────────────

    private fun sampleSet() = WebPFrameSplitter.split(
        riff(
            vp8x(0x02, 100, 80) +
                chunk("ANIM", listOf<Byte>(0, 0, 0, 0, 5, 0)) +
                anmf(100, 80, 40) +
                anmf(30, 40, 60, x = 10, y = 20, flags = 0b10) +
                anmf(20, 20, 0, x = 4, y = 6, flags = 0b01)
        )
    )

    @Test
    fun `split reads canvas size and loop count`() {
        val set = sampleSet()!!
        assertEquals(100, set.width)
        assertEquals(80, set.height)
        assertEquals(5, set.loopCount)
        assertEquals(3, set.frames.size)
    }

    @Test
    fun `split decodes frame offsets, which the container stores halved`() {
        val frames = sampleSet()!!.frames
        assertEquals(10, frames[1].x)
        assertEquals(20, frames[1].y)
        assertEquals(30, frames[1].width)
        assertEquals(40, frames[1].height)
        assertEquals(4, frames[2].x)
        assertEquals(6, frames[2].y)
    }

    @Test
    fun `split decodes durations verbatim including zero`() {
        val frames = sampleSet()!!.frames
        assertEquals(listOf(40, 60, 0), frames.map { it.durationMs })
        assertEquals(100, sampleSet()!!.totalDurationMs)
    }

    @Test
    fun `split decodes blend and dispose flags independently`() {
        val frames = sampleSet()!!.frames
        assertEquals(WebPFrameSplitter.Blend.BLEND, frames[0].blend)
        assertEquals(WebPFrameSplitter.Dispose.NONE, frames[0].dispose)
        assertEquals(WebPFrameSplitter.Blend.REPLACE, frames[1].blend)
        assertEquals(WebPFrameSplitter.Dispose.NONE, frames[1].dispose)
        assertEquals(WebPFrameSplitter.Blend.BLEND, frames[2].blend)
        assertEquals(WebPFrameSplitter.Dispose.BACKGROUND, frames[2].dispose)
    }

    @Test
    fun `averageFps derives from total duration`() {
        // 10 frames of 100ms = 10 fps
        val body = (0 until 10).flatMap { anmf(16, 16, 100) }
        val set = WebPFrameSplitter.split(riff(vp8x(0x02, 16, 16) + body))!!
        assertEquals(10.0, set.averageFps, 0.001)
    }

    // ── split: still-image rewrapping ───────────────────────────────────────

    @Test
    fun `a bare VP8 frame is wrapped in a minimal RIFF container`() {
        val set = WebPFrameSplitter.split(
            riff(vp8x(0x02, 16, 16) + anmf(16, 16, 100, subs = listOf("VP8 " to listOf<Byte>(7, 7, 7, 7))))
        )!!
        val bytes = set.frames[0].bytes

        assertEquals("RIFF", fourCCAt(bytes, 0))
        assertEquals("WEBP", fourCCAt(bytes, 8))
        // No alpha chunk means no synthesised VP8X — the image chunk follows directly
        assertEquals("VP8 ", fourCCAt(bytes, 12))
        assertEquals((bytes.size - 8).toLong(), readU32LE(bytes, 4))
    }

    @Test
    fun `a frame with a separate alpha chunk gets a synthesised VP8X header`() {
        val set = WebPFrameSplitter.split(
            riff(
                vp8x(0x02, 64, 48) + anmf(
                    64, 48, 100,
                    subs = listOf(
                        "ALPH" to listOf<Byte>(0, 1, 2, 3),
                        "VP8 " to listOf<Byte>(4, 5, 6, 7),
                    ),
                )
            )
        )!!
        val bytes = set.frames[0].bytes

        assertEquals("VP8X", fourCCAt(bytes, 12))
        assertEquals(0x10, bytes[20].toInt() and 0x10) // alpha flag set
        assertEquals("ALPH", fourCCAt(bytes, 30))
        assertEquals((bytes.size - 8).toLong(), readU32LE(bytes, 4))
    }

    @Test
    fun `the image bitstream is copied byte-for-byte, not truncated`() {
        // A one-byte-short copy still produces a structurally valid container
        // and a self-consistent RIFF size, but the bitstream is corrupt and no
        // decoder will accept it — so assert the actual payload bytes.
        val payload = (1..40).map { it.toByte() }
        val set = WebPFrameSplitter.split(
            riff(vp8x(0x02, 16, 16) + anmf(16, 16, 100, subs = listOf("VP8 " to payload)))
        )!!
        val bytes = set.frames[0].bytes

        // 12 bytes RIFF/WEBP header, then the VP8 chunk header, then the payload
        assertEquals("VP8 ", fourCCAt(bytes, 12))
        assertEquals(payload.size.toLong(), readU32LE(bytes, 16))
        val carried = bytes.copyOfRange(20, 20 + payload.size).toList()
        assertEquals(payload, carried)
        // Nothing lost and nothing extra
        assertEquals(20 + payload.size, bytes.size)
    }

    @Test
    fun `both sub-chunks of a lossy-with-alpha frame are copied in full`() {
        val alpha = (1..10).map { it.toByte() }
        val image = (100..130).map { it.toByte() }
        val set = WebPFrameSplitter.split(
            riff(
                vp8x(0x02, 32, 32) + anmf(
                    32, 32, 100,
                    subs = listOf("ALPH" to alpha, "VP8 " to image),
                )
            )
        )!!
        val bytes = set.frames[0].bytes

        // RIFF(12) + synthesised VP8X(18) = 30, then ALPH header at 30
        assertEquals("ALPH", fourCCAt(bytes, 30))
        assertEquals(alpha, bytes.copyOfRange(38, 38 + alpha.size).toList())
        // ALPH payload is padded to an even length (10 is already even)
        val vp8Offset = 38 + alpha.size
        assertEquals("VP8 ", fourCCAt(bytes, vp8Offset))
        assertEquals(
            image,
            bytes.copyOfRange(vp8Offset + 8, vp8Offset + 8 + image.size).toList(),
        )
    }

    @Test
    fun `a lossless VP8L frame is carried through unchanged`() {
        val set = WebPFrameSplitter.split(
            riff(vp8x(0x02, 8, 8) + anmf(8, 8, 50, subs = listOf("VP8L" to listOf<Byte>(0x2f, 1, 2, 3))))
        )!!
        val bytes = set.frames[0].bytes
        assertEquals("VP8L", fourCCAt(bytes, 12))
        assertEquals(0x2f, bytes[20].toInt() and 0xff) // VP8L signature survives
    }

    // ── split: malformed input ──────────────────────────────────────────────

    @Test
    fun `split does not throw when a frame declares more bytes than exist`() {
        val truncated = (
            fourCC("RIFF") + u32(1000) + fourCC("WEBP") +
                vp8x(0x02, 16, 16) +
                fourCC("ANMF") + u32(9999) + listOf<Byte>(0, 0, 0)
            ).toByteArray()
        // Must return rather than crash — a corrupt share shouldn't kill the flow
        WebPFrameSplitter.split(truncated)
    }

    @Test
    fun `split terminates on a chunk that fails to advance the cursor`() {
        val set = WebPFrameSplitter.split(
            riff(vp8x(0x02, 16, 16) + chunk("XMP ", emptyList()) + anmf(16, 16, 10))
        )
        assertNotNull(set)
        assertEquals(1, set!!.frames.size)
    }
}
