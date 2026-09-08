package app.embeddy.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M30: GIF/animated-WebP inputs must not be routed to an ExoPlayer surface. */
class MediaKindTest {

    @Test
    fun `gif and webp are animated-image files`() {
        assertTrue(isAnimatedImageFile("clip.gif"))
        assertTrue(isAnimatedImageFile("sticker.webp"))
        assertTrue(isAnimatedImageFile("SHOUTING.GIF"))
        assertTrue(isAnimatedImageFile("archive.tar.webp"))
    }

    @Test
    fun `video containers stay on the video path`() {
        assertFalse(isAnimatedImageFile("movie.mp4"))
        assertFalse(isAnimatedImageFile("movie.webm"))
        assertFalse(isAnimatedImageFile("movie.mkv"))
        assertFalse(isAnimatedImageFile("movie.mov"))
    }

    @Test
    fun `missing or odd extensions fall through to the video path`() {
        assertFalse(isAnimatedImageFile("file"))
        assertFalse(isAnimatedImageFile(""))
        assertFalse(isAnimatedImageFile("gif")) // no dot — "gif" is the whole name
        assertFalse(isAnimatedImageFile("ends.with.dot."))
    }
}
