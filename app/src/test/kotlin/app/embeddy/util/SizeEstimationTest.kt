package app.embeddy.util

import app.embeddy.conversion.ConversionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeEstimationTest {

    // --- estimateOutputWidth ---

    @Test
    fun `exact width overrides scaling`() {
        val config = ConversionConfig(maxDimension = 720, exactWidth = 400)
        assertEquals(400, SizeEstimation.estimateOutputWidth(1920, 1080, config))
    }

    @Test
    fun `maxDimension scales down landscape`() {
        val config = ConversionConfig(maxDimension = 720)
        val w = SizeEstimation.estimateOutputWidth(1920, 1080, config)
        // scale = min(720/1920, 720/1080) = 720/1920 = 0.375 → 1920*0.375 = 720
        assertEquals(720, w)
    }

    @Test
    fun `maxDimension scales down portrait`() {
        val config = ConversionConfig(maxDimension = 720)
        val w = SizeEstimation.estimateOutputWidth(1080, 1920, config)
        // scale = min(720/1080, 720/1920) = 720/1920 = 0.375 → 1080*0.375 = 405
        assertEquals(405, w)
    }

    @Test
    fun `no scaling when within maxDimension`() {
        val config = ConversionConfig(maxDimension = 720)
        assertEquals(640, SizeEstimation.estimateOutputWidth(640, 480, config))
    }

    // ── maxDimension must trigger when EITHER axis exceeds it (mirrors
    //    FFmpeg's force_original_aspect_ratio=decrease behavior) ──

    @Test
    fun `maxDimension scales width even when only height exceeds it`() {
        val config = ConversionConfig(maxDimension = 720)
        // FFmpeg: scale='min(720,iw)':'min(720,ih)':decrease → 500x1000 → 360x720
        assertEquals(360, SizeEstimation.estimateOutputWidth(500, 1000, config))
        assertEquals(720, SizeEstimation.estimateOutputHeight(500, 1000, config))
    }

    @Test
    fun `maxDimension scales height even when only width exceeds it`() {
        val config = ConversionConfig(maxDimension = 720)
        assertEquals(720, SizeEstimation.estimateOutputWidth(1000, 500, config))
        assertEquals(360, SizeEstimation.estimateOutputHeight(1000, 500, config))
    }

    // ── single exact dimension derives the other axis from aspect ratio
    //    (mirrors the engine's scale=W:-2 / scale=-2:H filters) ──

    @Test
    fun `exactHeight only derives width from aspect ratio, not maxDimension`() {
        val config = ConversionConfig(maxDimension = 720, exactHeight = 540)
        // Engine emits scale=-2:540 → 1920x1080 becomes 960x540
        assertEquals(960, SizeEstimation.estimateOutputWidth(1920, 1080, config))
        assertEquals(540, SizeEstimation.estimateOutputHeight(1920, 1080, config))
    }

    @Test
    fun `exactWidth only derives height from aspect ratio, not maxDimension`() {
        val config = ConversionConfig(maxDimension = 720, exactWidth = 540)
        // Engine emits scale=540:-2 → 1080x1920 becomes 540x960
        assertEquals(540, SizeEstimation.estimateOutputWidth(1080, 1920, config))
        assertEquals(960, SizeEstimation.estimateOutputHeight(1080, 1920, config))
    }

    // --- estimateOutputHeight ---

    @Test
    fun `exact height overrides scaling`() {
        val config = ConversionConfig(maxDimension = 720, exactHeight = 300)
        assertEquals(300, SizeEstimation.estimateOutputHeight(1920, 1080, config))
    }

    @Test
    fun `maxDimension scales height for landscape`() {
        val config = ConversionConfig(maxDimension = 720)
        val h = SizeEstimation.estimateOutputHeight(1920, 1080, config)
        // scale = 720/1920 = 0.375 → 1080*0.375 = 405
        assertEquals(405, h)
    }

    // --- estimateOutputBytes ---

    @Test
    fun `BPP model produces positive bytes for valid input`() {
        val bytes = SizeEstimation.estimateOutputBytes(
            width = 720, height = 405, durationMs = 5000,
            fps = 12, quality = 70,
        )
        assertTrue("Expected positive byte estimate, got $bytes", bytes > 0)
    }

    @Test
    fun `higher quality yields larger estimate`() {
        val low = SizeEstimation.estimateOutputBytes(
            width = 720, height = 405, durationMs = 5000, fps = 12, quality = 30,
        )
        val high = SizeEstimation.estimateOutputBytes(
            width = 720, height = 405, durationMs = 5000, fps = 12, quality = 90,
        )
        assertTrue("Higher quality ($high) should be larger than low ($low)", high > low)
    }

    @Test
    fun `longer duration yields larger estimate`() {
        val short = SizeEstimation.estimateOutputBytes(
            width = 720, height = 405, durationMs = 2000, fps = 12, quality = 70,
        )
        val long = SizeEstimation.estimateOutputBytes(
            width = 720, height = 405, durationMs = 10000, fps = 12, quality = 70,
        )
        assertTrue("Longer video ($long) should be larger than short ($short)", long > short)
    }

    @Test
    fun `fallback uses duration ratio when dimensions zero`() {
        val bytes = SizeEstimation.estimateOutputBytes(
            width = 0, height = 0, durationMs = 5000, fps = 12, quality = 70,
            inputSizeBytes = 10_000_000, totalDurationMs = 10_000,
        )
        // Fallback: 10MB * 5/10 * 0.3 = 1.5MB
        assertEquals(1_500_000L, bytes)
    }

    @Test
    fun `returns input size when all fallbacks fail`() {
        val bytes = SizeEstimation.estimateOutputBytes(
            width = 0, height = 0, durationMs = 5000, fps = 12, quality = 70,
            inputSizeBytes = 5_000_000, totalDurationMs = 0,
        )
        assertEquals(5_000_000L, bytes)
    }
}
