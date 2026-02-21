package app.embeddy.upload

import org.junit.Assert.*
import org.junit.Test

/** Tests for [UploadHost] enum constraints. */
class UploadHostTest {

    @Test
    fun `all hosts have positive max size`() {
        UploadHost.entries.forEach { host ->
            assertTrue("${host.name}: maxSizeMb > 0", host.maxSizeMb > 0)
        }
    }

    @Test
    fun `all hosts have non-empty labels`() {
        UploadHost.entries.forEach { host ->
            assertTrue("${host.name}: label not blank", host.label.isNotBlank())
        }
    }

    @Test
    fun `0x0 st has 512 MB limit`() {
        assertEquals(512, UploadHost.ZER0X0.maxSizeMb)
    }

    @Test
    fun `catbox has 200 MB limit`() {
        assertEquals(200, UploadHost.CATBOX.maxSizeMb)
    }

    @Test
    fun `upload result url field is stored`() {
        val result = UploadResult(
            url = "https://0x0.st/abc.webp",
            fileName = "test.webp",
            host = UploadHost.ZER0X0,
            fileSizeBytes = 1024,
        )
        assertEquals("https://0x0.st/abc.webp", result.url)
        assertEquals("test.webp", result.fileName)
        assertEquals(UploadHost.ZER0X0, result.host)
        assertEquals(1024L, result.fileSizeBytes)
    }
}
