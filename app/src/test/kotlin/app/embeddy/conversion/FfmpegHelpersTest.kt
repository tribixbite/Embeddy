package app.embeddy.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers in FfmpegCommands.kt that are not exercised via full commands elsewhere. */
class FfmpegHelpersTest {

    // ── target-size bitrate (M9) ──

    @Test
    fun `target-size bitrate fits the budget after audio`() {
        // 10 MB over 60 s with 128 kbps audio: 10e6*8*0.95/60 = 1_266_666 bps total
        assertEquals(1_266_666L - 128_000L, bitrateForTargetSize(10_000_000, 60_000, 128_000))
    }

    @Test
    fun `target-size bitrate is clamped and null when it cannot be computed`() {
        assertEquals(MIN_VIDEO_BITRATE_BPS, bitrateForTargetSize(10_000, 600_000, 128_000))
        assertEquals(MAX_VIDEO_BITRATE_BPS, bitrateForTargetSize(1_000_000_000, 1_000, 0))
        assertNull(bitrateForTargetSize(0, 60_000, 0))
        assertNull(bitrateForTargetSize(10_000_000, 0, 0))
    }

    @Test
    fun `next bitrate scales by the overshoot and always decreases`() {
        // 20% over → scaled by 1/1.2 * 0.95
        assertEquals((1_000_000 / 1.2 * 0.95).toLong(), nextBitrateForTarget(1_000_000, 12_000_000, 10_000_000))
        // Tiny overshoot still drops at least 10% so the loop terminates
        assertEquals(900_000L, nextBitrateForTarget(1_000_000, 10_000_001, 10_000_000))
        assertEquals(MIN_VIDEO_BITRATE_BPS, nextBitrateForTarget(MIN_VIDEO_BITRATE_BPS, 2, 1))
    }

    // ── even dimensions for video (M3) ──

    @Test
    fun `video scale filter forces even dimensions`() {
        val filters = scaleFilters(ConversionConfig(maxDimension = 720), forVideo = true)
        assertEquals(listOf("scale='min(720,iw)':'min(720,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2:flags=lanczos"), filters)
        assertFalse(scaleFilters(ConversionConfig(maxDimension = 720), forVideo = false).single().contains("force_divisible_by"))
    }

    @Test
    fun `odd exact dimensions are floored to even for video only`() {
        assertEquals("scale=404:-2:flags=lanczos", scaleFilters(ConversionConfig(exactWidth = 405), true).single())
        assertEquals("scale=405:-2:flags=lanczos", scaleFilters(ConversionConfig(exactWidth = 405), false).single())
        assertEquals(
            listOf("scale=320:240:force_original_aspect_ratio=increase:flags=lanczos", "crop=320:240"),
            scaleFilters(ConversionConfig(exactWidth = 321, exactHeight = 241), true),
        )
    }

    @Test
    fun `original resolution keeps the source size, trimmed to even for video`() {
        val original = ConversionConfig(maxDimension = ConversionConfig.ORIGINAL_DIMENSION)
        assertEquals(listOf("scale=trunc(iw/2)*2:trunc(ih/2)*2"), scaleFilters(original, forVideo = true))
        assertTrue(scaleFilters(original, forVideo = false).isEmpty())
    }

    // ── palette (M4) and metadata (L5) ──

    @Test
    fun `palette uses per-frame stats so frames are never buffered for the whole clip`() {
        val palette = paletteFilter(DitherMode.BAYER)
        assertTrue(palette, "stats_mode=single" in palette)
        assertTrue(palette, "paletteuse=new=1:dither=bayer" in palette)
        assertTrue(palette, "[pa]" in palette && "[pp]" in palette)
    }

    @Test
    fun `every output strips source metadata`() {
        val base = CommandRequest(InputSpec("/in.mp4", width = 640, height = 360), "/o.webp", OutputTarget.AnimatedWebp, ConversionConfig())
        assertTrue("-map_metadata -1" in buildFfmpegCommand(base))
        assertTrue("-map_metadata -1" in buildFfmpegCommand(base.copy(target = OutputTarget.Video(VideoContainer.MP4))))
    }

    @Test
    fun `original fps emits no fps filter`() {
        val req = CommandRequest(
            InputSpec("/in.mp4", width = 640, height = 360),
            "/o.mp4",
            OutputTarget.Video(VideoContainer.MP4),
            ConversionConfig(fps = ConversionConfig.ORIGINAL_FPS),
        )
        assertFalse(buildFfmpegCommand(req).contains("fps="))
    }

    @Test
    fun `explicit bitrate overrides the quality mapping`() {
        val req = CommandRequest(
            InputSpec("/in.mp4", width = 1920, height = 1080),
            "/o.mp4",
            OutputTarget.Video(VideoContainer.MP4),
            ConversionConfig(),
            videoBitrateBps = 777_000,
        )
        assertTrue(buildFfmpegCommand(req).contains("-b:v 777000 "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an edit that keeps nothing is rejected instead of producing a broken command`() {
        buildFfmpegCommand(
            CommandRequest(
                InputSpec("/in.mp4", durationMs = 10_000),
                "/o.webp",
                OutputTarget.AnimatedWebp,
                ConversionConfig(),
                edit = EditSpec(trimStartMs = 5_000, trimEndMs = 5_000),
            ),
        )
    }

    @Test
    fun `untrimmed input with a known duration gets no seek or duration flags`() {
        val cmd = buildFfmpegCommand(
            CommandRequest(InputSpec("/in.mp4", durationMs = 10_000), "/o.webp", OutputTarget.AnimatedWebp, ConversionConfig()),
        )
        assertFalse(cmd, "-ss " in cmd)
        assertFalse(cmd, "-t " in cmd)
    }

    // ── progress + error helpers ──

    @Test
    fun `progress uses the further-along signal and clamps`() {
        assertEquals(0.5f, progressFraction(60, 1_000.0, 120, 10_000), 1e-6f)
        assertEquals(0.8f, progressFraction(12, 8_000.0, 120, 10_000), 1e-6f)
        assertEquals(1f, progressFraction(500, 0.0, 120, 0), 1e-6f)
        assertEquals(0f, progressFraction(50, 900.0, 0, 0), 1e-6f)
    }

    @Test
    fun `expected frames use the estimation fps`() {
        assertEquals(120L, expectedFrameCount(10_000, ConversionConfig(fps = 12)))
        assertEquals(300L, expectedFrameCount(10_000, ConversionConfig(fps = 0)))
        assertEquals(0L, expectedFrameCount(0, ConversionConfig()))
    }

    @Test
    fun `ffmpeg error output maps to readable reasons`() {
        assertEquals("Conversion failed — no output from FFmpeg", ffmpegErrorReason(null))
        assertTrue(ffmpegErrorReason("Unknown encoder 'libx264'").startsWith("Encoder not available"))
        assertTrue(ffmpegErrorReason("[AVFilterGraph] No such filter: 'foo'").startsWith("FFmpeg filter not found"))
        assertEquals("File appears corrupted or uses an unsupported codec", ffmpegErrorReason("x: Invalid data found when processing input"))
        assertEquals("Out of memory — try a shorter clip or lower resolution", ffmpegErrorReason("Cannot allocate memory"))
        assertEquals("FFmpeg error: boom", ffmpegErrorReason("boom"))
    }
}
