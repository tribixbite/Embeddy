package app.embeddy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FileInfoUtils.sanitizeFileName].
 *
 * `DISPLAY_NAME` comes from whichever content provider served the URI, so it is
 * untrusted input that ends up in `File(dir, name)` and in FFmpeg command strings.
 */
class FileInfoUtilsTest {

    @Test
    fun `ordinary names pass through unchanged`() {
        assertEquals("holiday clip.mp4", FileInfoUtils.sanitizeFileName("holiday clip.mp4"))
        assertEquals("IMG_2024-01-02.gif", FileInfoUtils.sanitizeFileName("IMG_2024-01-02.gif"))
    }

    @Test
    fun `path separators are stripped so the name cannot escape its directory`() {
        assertFalse(FileInfoUtils.sanitizeFileName("../../databases/x.mp4").contains("/"))
        assertEquals("x.mp4", FileInfoUtils.sanitizeFileName("../../databases/x.mp4"))
        assertEquals("evil.gif", FileInfoUtils.sanitizeFileName("""..\..\evil.gif"""))
    }

    @Test
    fun `leading and trailing dots are trimmed`() {
        assertEquals("file", FileInfoUtils.sanitizeFileName(".."))
        assertEquals("hidden.mp4", FileInfoUtils.sanitizeFileName(".hidden.mp4"))
    }

    @Test
    fun `quotes and shell metacharacters are replaced`() {
        val sanitized = FileInfoUtils.sanitizeFileName("""a"b;rm -rf$'c.mp4""")
        assertFalse(sanitized.contains("\""))
        assertFalse(sanitized.contains("'"))
        assertFalse(sanitized.contains(";"))
        assertFalse(sanitized.contains("$"))
    }

    @Test
    fun `control characters and newlines are replaced`() {
        val sanitized = FileInfoUtils.sanitizeFileName("bad\r\nname.mp4")
        assertFalse(sanitized.contains("\r"))
        assertFalse(sanitized.contains("\n"))
    }

    @Test
    fun `blank or fully stripped names fall back`() {
        assertEquals("file", FileInfoUtils.sanitizeFileName(""))
        assertEquals("file", FileInfoUtils.sanitizeFileName("   "))
        assertEquals("output", FileInfoUtils.sanitizeFileName("///", fallback = "output"))
    }

    @Test
    fun `overlong names are truncated`() {
        val sanitized = FileInfoUtils.sanitizeFileName("a".repeat(500) + ".mp4")
        assertTrue(sanitized.length <= 120)
    }

    @Test
    fun `unicode names degrade to a usable ascii name rather than being dropped`() {
        val sanitized = FileInfoUtils.sanitizeFileName("café☕.gif")
        assertTrue(sanitized.endsWith(".gif"))
        assertTrue(sanitized.isNotBlank())
    }
}
