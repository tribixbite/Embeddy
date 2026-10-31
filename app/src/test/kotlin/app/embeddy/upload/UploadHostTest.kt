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
    fun `0x0 st has 512 MB limit`() {
        assertEquals(512, UploadHost.ZER0X0.maxSizeMb)
    }

    @Test
    fun `catbox has 200 MB limit`() {
        assertEquals(200, UploadHost.CATBOX.maxSizeMb)
    }

    // ── EXIF strip support (M12) ──

    @Test
    fun `exif strip is claimed only for formats ExifInterface can save`() {
        assertTrue(UploadEngine.isExifSupported("image/jpeg"))
        assertTrue(UploadEngine.isExifSupported("image/png"))
        assertTrue(UploadEngine.isExifSupported("image/webp"))
    }

    @Test
    fun `heif and heic are rejected because saveAttributes cannot write them`() {
        // androidx ExifInterface.saveAttributes() throws UnsupportedOperationException
        // for HEIF — claiming support let GPS EXIF reach public hosts unstripped.
        assertFalse(UploadEngine.isExifSupported("image/heif"))
        assertFalse(UploadEngine.isExifSupported("image/heic"))
    }

    @Test
    fun `non-image mime types are not exif-strippable`() {
        assertFalse(UploadEngine.isExifSupported("video/mp4"))
        assertFalse(UploadEngine.isExifSupported("application/octet-stream"))
    }
}
