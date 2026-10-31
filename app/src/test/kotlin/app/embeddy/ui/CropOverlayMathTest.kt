package app.embeddy.ui

import app.embeddy.conversion.CropRect
import app.embeddy.ui.components.CropHandle
import app.embeddy.ui.components.FittedRect
import app.embeddy.ui.components.dragCrop
import app.embeddy.ui.components.fitContentRect
import app.embeddy.ui.components.formatSpeed
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure geometry behind the crop overlay. */
class CropOverlayMathTest {

    @Test
    fun `portrait video in a 16 by 9 box is pillarboxed`() {
        // scale = min(640/1080, 360/1920) = 0.1875 → 202.5 x 360, centered
        assertEquals(FittedRect(218.75f, 0f, 202.5f, 360f), fitContentRect(640f, 360f, 1080, 1920))
    }

    @Test
    fun `unknown content fills the box`() {
        assertEquals(FittedRect(0f, 0f, 640f, 360f), fitContentRect(640f, 360f, 0, 0))
    }

    @Test
    fun `corner drags resize from the opposite corner`() {
        val r = dragCrop(CropRect.FULL, CropHandle.TOP_LEFT, 0.2f, 0.1f)
        assertEquals(CropRect(0.2f, 0.1f, 1f, 1f), r)
    }

    @Test
    fun `move keeps the size and stops at the frame edge`() {
        val r = dragCrop(CropRect(0.1f, 0.1f, 0.5f, 0.5f), CropHandle.MOVE, 0.9f, -0.3f)
        assertEquals(0.4f, r.right - r.left, 1e-5f)
        assertEquals(1f, r.right, 1e-5f)
        assertEquals(0f, r.top, 1e-5f)
    }

    @Test
    fun `speed labels drop trailing zeros`() {
        assertEquals("0.25", formatSpeed(0.25f))
        assertEquals("1", formatSpeed(1f))
        assertEquals("1.5", formatSpeed(1.5f))
    }
}
