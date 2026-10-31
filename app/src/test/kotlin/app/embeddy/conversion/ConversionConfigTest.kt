package app.embeddy.conversion

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [ConversionConfig], [TrimSegment], and [Preset] logic.
 * These are pure Kotlin data classes with no Android dependencies.
 */
class ConversionConfigTest {

    // ── TrimSegment ──

    @Test
    fun `TrimSegment durationMs calculated correctly`() {
        val seg = TrimSegment(startMs = 1000, endMs = 5000)
        assertEquals(4000, seg.durationMs)
    }

    @Test
    fun `TrimSegment durationMs is zero when start equals end`() {
        val seg = TrimSegment(startMs = 3000, endMs = 3000)
        assertEquals(0, seg.durationMs)
    }

    @Test
    fun `TrimSegment durationMs coerces negative to zero`() {
        val seg = TrimSegment(startMs = 5000, endMs = 3000)
        assertEquals(0, seg.durationMs)
    }

    @Test
    fun `TrimSegment volume defaults to unity gain`() {
        assertEquals(1f, TrimSegment(0, 1000).volume)
    }

    @Test
    fun `TrimSegment carries an explicit volume`() {
        assertEquals(0.5f, TrimSegment(0, 1000, volume = 0.5f).volume)
    }

    // ── ConversionConfig.effectiveMinQuality ──

    @Test
    fun `effectiveMinQuality keeps the preset floor when start quality is above it`() {
        val config = ConversionConfig.fromPreset(Preset.DISCORD)
        assertEquals(70, config.startQuality)
        assertEquals(50, config.effectiveMinQuality)
    }

    @Test
    fun `effectiveMinQuality clamps to start quality when the user drops below the floor`() {
        // The slider allows 30 while the Discord preset's floor is 50 — without the
        // clamp the adaptive loop would run zero attempts and the conversion would fail.
        val config = ConversionConfig.fromPreset(Preset.DISCORD).copy(startQuality = 30)
        assertEquals(30, config.effectiveMinQuality)
        assertTrue(config.startQuality >= config.effectiveMinQuality)
    }

    @Test
    fun `effectiveMinQuality never exceeds start quality for any preset at slider minimum`() {
        // 30 is the lowest value the quality slider in SettingsPanel can produce.
        Preset.entries.forEach { preset ->
            val config = ConversionConfig.fromPreset(preset).copy(startQuality = 30)
            assertTrue(
                "${preset.name} would run zero encode attempts",
                config.startQuality >= config.effectiveMinQuality,
            )
        }
    }

    // ── Preset.fromPreset ──

    @Test
    fun `fromPreset Discord creates correct config`() {
        val config = ConversionConfig.fromPreset(Preset.DISCORD)
        assertEquals(720, config.maxDimension)
        assertEquals(12, config.fps)
        assertEquals(70, config.startQuality)
        assertEquals(10_000_000L, config.targetSizeBytes)
        assertTrue(config.sharpen)
        assertEquals(Preset.DISCORD, config.preset)
    }

    @Test
    fun `fromPreset Telegram creates sticker-friendly config`() {
        val config = ConversionConfig.fromPreset(Preset.TELEGRAM)
        assertEquals(512, config.maxDimension)
        assertEquals(30, config.fps)
        assertEquals(256_000L, config.targetSizeBytes)
        assertFalse(config.sharpen)
        assertEquals(Preset.TELEGRAM, config.preset)
    }

    @Test
    fun `fromPreset Slack creates correct config`() {
        val config = ConversionConfig.fromPreset(Preset.SLACK)
        assertEquals(640, config.maxDimension)
        assertEquals(5_000_000L, config.targetSizeBytes)
        assertEquals(Preset.SLACK, config.preset)
    }

    // ── Default values ──

    @Test
    fun `default config has sensible defaults`() {
        val config = ConversionConfig()
        assertEquals(0, config.exactWidth)
        assertEquals(0, config.exactHeight)
        assertEquals(0, config.denoiseStrength)
        assertEquals(ColorSpace.AUTO, config.colorSpace)
        assertEquals(DitherMode.NONE, config.ditherMode)
        assertEquals(OutputMediaFormat.WEBP, config.outputFormat)
    }

    @Test
    fun `only container formats count as video outputs`() {
        assertTrue(OutputMediaFormat.MP4.isVideo)
        assertTrue(OutputMediaFormat.ORIGINAL.isVideo)
        assertFalse(OutputMediaFormat.WEBP.isVideo)
    }

    @Test
    fun `original fps falls back to 30 for estimates only`() {
        assertEquals(30, ConversionConfig(fps = ConversionConfig.ORIGINAL_FPS).estimationFps)
        assertEquals(12, ConversionConfig(fps = 12).estimationFps)
    }

    // ── Enum values ──

    @Test
    fun `ColorSpace ffmpegValue matches expected`() {
        assertEquals("", ColorSpace.AUTO.ffmpegValue)
        assertEquals("yuv420p", ColorSpace.YUV420.ffmpegValue)
    }

    @Test
    fun `ColorSpace only offers formats libwebp_anim actually consumes`() {
        // yuv444p/rgb24 were silently auto-substituted with bgra by FFmpeg,
        // so YUV444/RGB were persisted no-ops and must not come back.
        assertEquals(listOf(ColorSpace.AUTO, ColorSpace.YUV420), ColorSpace.entries.toList())
    }

    @Test
    fun `DitherMode ffmpegValue matches expected`() {
        assertEquals("", DitherMode.NONE.ffmpegValue)
        assertEquals("bayer", DitherMode.BAYER.ffmpegValue)
        assertEquals("floyd_steinberg", DitherMode.FLOYD_STEINBERG.ffmpegValue)
        assertEquals("sierra2_4a", DitherMode.SIERRA.ffmpegValue)
    }
}
