package app.embeddy.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {

    @Test
    fun `clip times show tenths and switch to minutes at 60 s`() {
        assertEquals("0.0s", formatClipTime(0))
        assertEquals("7.4s", formatClipTime(7_499))
        assertEquals("1:05.3", formatClipTime(65_300))
        assertEquals("0.0s", formatClipTime(-5))
    }

    @Test
    fun `media durations show whole seconds`() {
        assertEquals("42s", formatMediaDuration(42_900))
        assertEquals("2m 05s", formatMediaDuration(125_000))
    }
}
