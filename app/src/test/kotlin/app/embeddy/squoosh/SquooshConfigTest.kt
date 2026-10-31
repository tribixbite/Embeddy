package app.embeddy.squoosh

import org.junit.Assert.*
import org.junit.Test

/** Tests for [SquooshConfig], [SquooshResult], and [OutputFormat]. */
class SquooshConfigTest {

    // ── OutputFormat ──

    @Test
    fun `each format's declared MIME type matches what share and save derive from its extension`() {
        // Share intents and MediaStore rows use FileInfoUtils.mimeTypeForFile on
        // the output name; a mismatch would advertise the wrong type.
        OutputFormat.entries.forEach { format ->
            assertEquals(format.name, format.mimeType, app.embeddy.util.FileInfoUtils.mimeTypeForFile("x.${format.extension}"))
        }
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
