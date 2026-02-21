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
