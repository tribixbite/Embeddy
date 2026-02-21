package app.embeddy.squoosh

import org.junit.Assert.*
import org.junit.Test

/** Tests for [SquooshConfig], [SquooshResult], and [OutputFormat]. */
class SquooshConfigTest {

    // ── OutputFormat ──

    @Test
    fun `output formats have correct extensions`() {
        assertEquals("webp", OutputFormat.WEBP.extension)
        assertEquals("jpg", OutputFormat.JPEG.extension)
        assertEquals("png", OutputFormat.PNG.extension)
        assertEquals("avif", OutputFormat.AVIF.extension)
    }

    @Test
    fun `output formats have valid MIME types`() {
        assertEquals("image/webp", OutputFormat.WEBP.mimeType)
        assertEquals("image/jpeg", OutputFormat.JPEG.mimeType)
        assertEquals("image/png", OutputFormat.PNG.mimeType)
        assertEquals("image/avif", OutputFormat.AVIF.mimeType)
    }

    // ── SquooshConfig defaults ──

    @Test
    fun `default config uses WebP at quality 80`() {
        val config = SquooshConfig()
        assertEquals(OutputFormat.WEBP, config.format)
        assertEquals(80, config.quality)
        assertFalse(config.lossless)
        assertEquals(0, config.maxDimension)
        assertEquals(0, config.exactWidth)
        assertEquals(0, config.exactHeight)
    }

    // ── SquooshResult ──

    @Test
    fun `savings percent calculated correctly`() {
        val result = SquooshResult(
            outputPath = "/test.webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 300,
            originalWidth = 100,
            originalHeight = 100,
            outputWidth = 100,
            outputHeight = 100,
            format = OutputFormat.WEBP,
            quality = 80,
        )
        assertEquals(70f, result.savingsPercent, 0.1f)
    }

    @Test
    fun `savings percent is zero for zero original`() {
        val result = SquooshResult(
            outputPath = "/test.webp",
            originalSizeBytes = 0,
            compressedSizeBytes = 100,
            originalWidth = 100,
            originalHeight = 100,
            outputWidth = 100,
            outputHeight = 100,
            format = OutputFormat.WEBP,
            quality = 80,
        )
        assertEquals(0f, result.savingsPercent, 0.01f)
    }

    @Test
    fun `savings percent negative when compressed is larger`() {
        val result = SquooshResult(
            outputPath = "/test.png",
            originalSizeBytes = 500,
            compressedSizeBytes = 700,
            originalWidth = 100,
            originalHeight = 100,
            outputWidth = 100,
            outputHeight = 100,
            format = OutputFormat.PNG,
            quality = 100,
        )
        // (500 - 700) / 500 * 100 = -40%
        assertEquals(-40f, result.savingsPercent, 0.1f)
    }

    @Test
    fun `result with exact 50 percent savings`() {
        val result = SquooshResult(
            outputPath = "/test.jpg",
            originalSizeBytes = 2000,
            compressedSizeBytes = 1000,
            originalWidth = 200,
            originalHeight = 200,
            outputWidth = 100,
            outputHeight = 100,
            format = OutputFormat.JPEG,
            quality = 75,
        )
        assertEquals(50f, result.savingsPercent, 0.01f)
    }
}
