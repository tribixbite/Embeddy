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

    // ── ConversionConfig.totalKeptDurationMs ──

    @Test
    fun `totalKeptDurationMs returns zero when no trim and no segments`() {
        val config = ConversionConfig()
        assertEquals(0L, config.totalKeptDurationMs)
    }

    @Test
    fun `totalKeptDurationMs from single trim range`() {
        val config = ConversionConfig(trimStartMs = 2000, trimEndMs = 8000)
        assertEquals(6000L, config.totalKeptDurationMs)
    }

    @Test
    fun `totalKeptDurationMs from segments overrides trim range`() {
        val config = ConversionConfig(
            trimStartMs = 0,
            trimEndMs = 10000,
            segments = listOf(
                TrimSegment(0, 3000),   // 3s
                TrimSegment(5000, 8000), // 3s
            ),
        )
        assertEquals(6000L, config.totalKeptDurationMs)
    }

    @Test
    fun `totalKeptDurationMs with multiple segments sums correctly`() {
        val config = ConversionConfig(
            segments = listOf(
                TrimSegment(0, 1000),
                TrimSegment(2000, 3500),
                TrimSegment(7000, 10000),
            ),
        )
        // 1000 + 1500 + 3000 = 5500
        assertEquals(5500L, config.totalKeptDurationMs)
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
        assertEquals(0, config.keyframeInterval)
        assertEquals(ColorSpace.AUTO, config.colorSpace)
        assertEquals(DitherMode.NONE, config.ditherMode)
        assertTrue(config.segments.isEmpty())
        assertEquals(0L, config.trimStartMs)
        assertEquals(0L, config.trimEndMs)
    }

    // ── Enum values ──

    @Test
    fun `ColorSpace ffmpegValue matches expected`() {
        assertEquals("", ColorSpace.AUTO.ffmpegValue)
        assertEquals("yuv420p", ColorSpace.YUV420.ffmpegValue)
        assertEquals("yuv444p", ColorSpace.YUV444.ffmpegValue)
        assertEquals("rgb24", ColorSpace.RGB.ffmpegValue)
    }

    @Test
    fun `DitherMode ffmpegValue matches expected`() {
        assertEquals("", DitherMode.NONE.ffmpegValue)
        assertEquals("bayer", DitherMode.BAYER.ffmpegValue)
        assertEquals("floyd_steinberg", DitherMode.FLOYD_STEINBERG.ffmpegValue)
        assertEquals("sierra2_4a", DitherMode.SIERRA.ffmpegValue)
    }

    // ── Copy & modify ──

    @Test
    fun `config copy preserves segments`() {
        val original = ConversionConfig(
            segments = listOf(TrimSegment(0, 5000)),
            preset = Preset.CUSTOM,
        )
        val copied = original.copy(fps = 24)
        assertEquals(1, copied.segments.size)
        assertEquals(0L, copied.segments[0].startMs)
        assertEquals(5000L, copied.segments[0].endMs)
        assertEquals(24, copied.fps)
    }
}
